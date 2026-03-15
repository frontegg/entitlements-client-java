package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Package-private query strategy that handles {@link PermissionRequestContext} checks.
 *
 * <p>For each permission key in the request, sends two items in a single
 * {@code CheckBulkPermissions} request:
 * <ol>
 *   <li>subject {@code frontegg_user:<base64(userId)>} → resource
 *       {@code frontegg_permission:<base64(permissionKey)>}, relation {@code entitled}</li>
 *   <li>subject {@code frontegg_tenant:<base64(tenantId)>} → resource
 *       {@code frontegg_permission:<base64(permissionKey)>}, relation {@code entitled}</li>
 * </ol>
 *
 * <p>N permission keys produce 2N items in a single {@code CheckBulkPermissions} request.
 *
 * <p>Returns {@link EntitlementsResult#allowed()} only if <em>every</em> permission key has at
 * least one pair (user or tenant) returning {@code PERMISSIONSHIP_HAS_PERMISSION}. If any
 * permission key is not entitled, returns {@link EntitlementsResult#denied()}.
 *
 * <p>The gRPC call is performed via the injected {@link BulkPermissionsExecutor}, which
 * allows tests to provide a simple lambda without needing to mock the final blocking stub.
 */
class PermissionSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(PermissionSpiceDBQuery.class);

    private static final String RELATION_ENTITLED = "entitled";
    private static final String TYPE_USER = "frontegg_user";
    private static final String TYPE_TENANT = "frontegg_tenant";
    private static final String TYPE_PERMISSION = "frontegg_permission";

    private final BulkPermissionsExecutor executor;
    private final Supplier<Consistency> consistencySupplier;

    PermissionSpiceDBQuery(BulkPermissionsExecutor executor, Supplier<Consistency> consistencySupplier) {
        this.executor = executor;
        this.consistencySupplier = consistencySupplier;
    }

    /**
     * Executes a permission entitlement check for the given user and permission keys.
     *
     * <p>ALL permission keys must be entitled for the result to be {@link EntitlementsResult#allowed()}.
     * If ANY key is not entitled, returns {@link EntitlementsResult#denied()}.
     *
     * @param userCtx       the user subject context (userId, tenantId, optional attributes)
     * @param permissionCtx the permission request context (one or more permission keys)
     * @return {@link EntitlementsResult#allowed()} if the user or tenant is entitled to every key;
     *         {@link EntitlementsResult#denied()} otherwise
     */
    EntitlementsResult query(UserSubjectContext userCtx, PermissionRequestContext permissionCtx) {
        String b64UserId = Base64Utils.encode(userCtx.userId());
        String b64TenantId = Base64Utils.encode(userCtx.tenantId());

        log.debug("Permission check userId={} tenantId={} permissionKeys={}",
                userCtx.userId(), userCtx.tenantId(), permissionCtx.permissionKeys());

        Struct caveatContext = CaveatContextBuilder.build(userCtx.attributes(), permissionCtx.at());

        CheckBulkPermissionsRequest.Builder requestBuilder = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(consistencySupplier.get());

        for (String permissionKey : permissionCtx.permissionKeys()) {
            String b64PermissionKey = Base64Utils.encode(permissionKey);

            ObjectReference permissionResource = ObjectReference.newBuilder()
                    .setObjectType(TYPE_PERMISSION)
                    .setObjectId(b64PermissionKey)
                    .build();

            requestBuilder.addItems(buildItem(TYPE_USER, b64UserId, permissionResource, caveatContext));
            requestBuilder.addItems(buildItem(TYPE_TENANT, b64TenantId, permissionResource, caveatContext));
        }

        CheckBulkPermissionsResponse response = executor.execute(requestBuilder.build());

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
                    + "userId={} permissionKeys={} — treating as denied (fail-closed). "
                    + "Ensure caveat context is fully populated.",
                    userCtx.userId(), permissionCtx.permissionKeys());
        }

        // Collect all resource IDs (base64 permission keys) that have PERMISSIONSHIP_HAS_PERMISSION
        Set<String> entitledPermissionIds = response.getPairsList().stream()
                .filter(CheckBulkPermissionsPair::hasItem)
                .filter(pair -> pair.getItem().getPermissionship()
                        == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)
                .map(pair -> pair.getRequest().getResource().getObjectId())
                .collect(Collectors.toSet());

        // ALL permission keys must be entitled
        boolean allEntitled = permissionCtx.permissionKeys().stream()
                .map(Base64Utils::encode)
                .allMatch(entitledPermissionIds::contains);

        log.debug("Permission check result allEntitled={} userId={} permissionKeys={}",
                allEntitled, userCtx.userId(), permissionCtx.permissionKeys());

        return allEntitled ? EntitlementsResult.allowed() : EntitlementsResult.denied();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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
