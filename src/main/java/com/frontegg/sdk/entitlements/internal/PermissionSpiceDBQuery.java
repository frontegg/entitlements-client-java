package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.LookupSubjectsRequest;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Package-private query strategy that handles {@link PermissionRequestContext} checks.
 *
 * <p>Sends two items in a single {@code CheckBulkPermissions} request for the permission key:
 * <ol>
 *   <li>subject {@code frontegg_user:<base64(userId)>} → resource
 *       {@code frontegg_permission:<base64(permissionKey)>}, relation {@code entitled}</li>
 *   <li>subject {@code frontegg_tenant:<base64(tenantId)>} → resource
 *       {@code frontegg_permission:<base64(permissionKey)>}, relation {@code entitled}</li>
 * </ol>
 *
 * <p>Returns {@link EntitlementsResult#allowed()} if <em>either</em> the user or tenant item
 * returns {@code PERMISSIONSHIP_HAS_PERMISSION}; otherwise returns
 * {@link EntitlementsResult#denied()}.
 *
 * <p>Before making the SpiceDB call, a two-stage short-circuit is applied:
 * <ol>
 *   <li>If the caller supplied a permissions cache ({@link UserSubjectContext#permissions()}),
 *       the requested key must match at least one pattern — otherwise deny immediately.</li>
 *   <li>If the permission is not linked to any {@code frontegg_feature} in SpiceDB (checked via
 *       {@code LookupSubjects}), it is self-sufficient and the user is allowed immediately.</li>
 * </ol>
 *
 * <p>The gRPC call is performed via the injected {@link BulkPermissionsExecutor}, which
 * allows tests to provide a simple lambda without needing to mock the final blocking stub.
 */
class PermissionSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(PermissionSpiceDBQuery.class);

    private static final String RELATION_ENTITLED = "access";
    private static final String TYPE_USER = "frontegg_user";
    private static final String TYPE_TENANT = "frontegg_tenant";
    private static final String TYPE_PERMISSION = "frontegg_permission";
    private static final String TYPE_FEATURE = "frontegg_feature";
    private static final String RELATION_PARENT = "parent";

    /** TTL for the feature-link topology cache in milliseconds. */
    private static final long FEATURE_LINK_CACHE_TTL_MS = 60_000L;

    private final BulkPermissionsExecutor executor;
    private final LookupSubjectsExecutor lookupSubjectsExecutor;
    private final Supplier<Consistency> consistencySupplier;
    private final long featureLinkCacheTtlMs;

    /**
     * Cache: base64-encoded permission key → timestamp (ms) at which we last confirmed the
     * permission IS linked to a feature.  Only {@code true} results are cached; absence of
     * a key (or an expired entry) means the result is unknown and SpiceDB must be re-queried.
     * Never storing {@code false} prevents a stale cache entry from causing a false-allow bypass
     * when a permission later gets linked to a feature.
     */
    private final ConcurrentHashMap<String, Long> featureLinkCache = new ConcurrentHashMap<>();

    PermissionSpiceDBQuery(BulkPermissionsExecutor executor,
                           LookupSubjectsExecutor lookupSubjectsExecutor,
                           Supplier<Consistency> consistencySupplier) {
        this(executor, lookupSubjectsExecutor, consistencySupplier, FEATURE_LINK_CACHE_TTL_MS);
    }

    /** Package-private constructor that allows overriding the cache TTL (used in tests). */
    PermissionSpiceDBQuery(BulkPermissionsExecutor executor,
                           LookupSubjectsExecutor lookupSubjectsExecutor,
                           Supplier<Consistency> consistencySupplier,
                           long featureLinkCacheTtlMs) {
        this.executor = executor;
        this.lookupSubjectsExecutor = lookupSubjectsExecutor;
        this.consistencySupplier = consistencySupplier;
        this.featureLinkCacheTtlMs = featureLinkCacheTtlMs;
    }

    /**
     * Executes a permission entitlement check for the given user and permission key.
     *
     * @param userCtx       the user subject context (userId, tenantId, optional attributes)
     * @param permissionCtx the permission request context (single permission key)
     * @return {@link EntitlementsResult#allowed()} if the user or tenant is entitled;
     *         {@link EntitlementsResult#denied()} otherwise
     */
    EntitlementsResult query(UserSubjectContext userCtx, PermissionRequestContext permissionCtx) {
        String b64UserId = Base64Utils.encode(userCtx.userId());
        String b64TenantId = Base64Utils.encode(userCtx.tenantId());
        String permissionKey = permissionCtx.permissionKey();

        log.debug("Permission check userId={} tenantId={} permissionKey={}",
                userCtx.userId(), userCtx.tenantId(), permissionKey);

        // Step 1: client-side cache check — if the caller supplied a permissions list,
        // the requested key must match at least one pattern; if not → deny immediately.
        List<String> cachedPermissions = userCtx.permissions();
        if (!cachedPermissions.isEmpty()) {
            if (!PermissionPatternMatcher.matches(permissionKey, cachedPermissions)) {
                log.debug("Permission cache miss userId={} permissionKey={} — denying without SpiceDB call",
                        userCtx.userId(), permissionKey);
                return EntitlementsResult.denied();
            }
        }

        // Step 2: feature-linking check — if the permission is not linked to any feature
        // in SpiceDB, it is self-sufficient → allow immediately.
        if (!anyPermissionLinkedToFeature(List.of(permissionKey))) {
            log.debug("No features linked to permission={} userId={} — allowing without CheckBulkPermissions",
                    permissionKey, userCtx.userId());
            return EntitlementsResult.allowed();
        }

        Struct caveatContext = CaveatContextBuilder.build(userCtx.attributes(), null);

        String b64PermissionKey = Base64Utils.encode(permissionKey);

        ObjectReference permissionResource = ObjectReference.newBuilder()
                .setObjectType(TYPE_PERMISSION)
                .setObjectId(b64PermissionKey)
                .build();

        CheckBulkPermissionsRequest request = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(consistencySupplier.get())
                .addItems(buildItem(TYPE_USER, b64UserId, permissionResource, caveatContext))
                .addItems(buildItem(TYPE_TENANT, b64TenantId, permissionResource, caveatContext))
                .build();

        CheckBulkPermissionsResponse response = executor.execute(request);

        for (CheckBulkPermissionsPair pair : response.getPairsList()) {
            if (pair.hasError()) {
                throw new EntitlementsQueryException(
                        "SpiceDB returned an error for bulk permission check: " + pair.getError().getMessage());
            }
        }

        boolean hasConditional = response.getPairsList().stream()
                .filter(CheckBulkPermissionsPair::hasItem)
                .anyMatch(pair -> pair.getItem().getPermissionship()
                        == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION);
        if (hasConditional) {
            log.warn("SpiceDB returned CONDITIONAL_PERMISSION for permission check "
                    + "userId={} permissionKey={} — treating as denied (fail-closed). "
                    + "Ensure caveat context is fully populated.",
                    userCtx.userId(), permissionKey);
        }

        boolean entitled = response.getPairsList().stream()
                .filter(CheckBulkPermissionsPair::hasItem)
                .anyMatch(pair -> pair.getItem().getPermissionship()
                        == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);

        log.debug("Permission check result entitled={} userId={} permissionKey={}",
                entitled, userCtx.userId(), permissionKey);

        return entitled ? EntitlementsResult.allowed() : EntitlementsResult.denied();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if any of the given permission keys has at least one
     * {@code frontegg_feature} subject linked via the {@code parent} relation in SpiceDB.
     *
     * <p>If any key is feature-linked the full {@code CheckBulkPermissions} path must run.
     * If none are linked the permission is self-sufficient and the caller can return
     * {@link EntitlementsResult#allowed()} immediately.
     */
    private boolean anyPermissionLinkedToFeature(List<String> permissionKeys) {
        for (String permissionKey : permissionKeys) {
            String b64PermissionKey = Base64Utils.encode(permissionKey);

            // Only trust a cached entry if it records a confirmed "linked" result AND is fresh.
            // - Present + not expired  → feature IS linked, safe to skip SpiceDB (worst case: an
            //   extra call after the link is removed, which fails closed).
            // - Present + expired      → remove the stale entry, fall through to SpiceDB.
            // - Absent                 → result unknown, always call SpiceDB fresh.
            // We never cache false, so absence always means "unknown".
            Long cachedTimestamp = featureLinkCache.get(b64PermissionKey);
            if (cachedTimestamp != null) {
                if (System.currentTimeMillis() - cachedTimestamp < featureLinkCacheTtlMs) {
                    // Cache hit: we previously confirmed this permission is linked to a feature.
                    return true;
                }
                // Cache expired — remove atomically using the two-arg form so a concurrent
                // thread that already refreshed the entry isn't evicted.
                featureLinkCache.remove(b64PermissionKey, cachedTimestamp);
            }

            LookupSubjectsRequest request = LookupSubjectsRequest.newBuilder()
                    .setConsistency(consistencySupplier.get())
                    .setResource(ObjectReference.newBuilder()
                            .setObjectType(TYPE_PERMISSION)
                            .setObjectId(b64PermissionKey)
                            .build())
                    .setPermission(RELATION_PARENT)
                    .setSubjectObjectType(TYPE_FEATURE)
                    .build();

            Iterator<com.authzed.api.v1.LookupSubjectsResponse> it =
                    lookupSubjectsExecutor.execute(request);
            boolean linked = it.hasNext();

            // Only cache positive results — never cache false.
            if (linked) {
                featureLinkCache.put(b64PermissionKey, System.currentTimeMillis());
                return true;
            }
        }
        return false;
    }

    private static CheckBulkPermissionsRequestItem buildItem(
            String subjectType,
            String subjectId,
            ObjectReference resource,
            Struct caveatContext) {

        SubjectReference subject = SubjectReference.newBuilder()
                .setObject(ObjectReference.newBuilder()
                        .setObjectType(subjectType)
                        .setObjectId(subjectId)
                        .build())
                .build();

        CheckBulkPermissionsRequestItem.Builder itemBuilder =
                CheckBulkPermissionsRequestItem.newBuilder()
                        .setSubject(subject)
                        .setResource(resource)
                        .setPermission(RELATION_ENTITLED);

        if (caveatContext != null) {
            itemBuilder.setContext(caveatContext);
        }

        return itemBuilder.build();
    }
}
