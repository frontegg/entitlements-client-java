package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.ReadRelationshipsRequest;
import com.authzed.api.v1.ReadRelationshipsResponse;
import com.authzed.api.v1.RelationshipFilter;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Package-private query strategy that handles {@link RouteRequestContext} checks using a
 * policy engine backed by SpiceDB route relationships.
 *
 * <p>On each query the strategy:
 * <ol>
 *   <li>Fetches all {@code frontegg_route} relationships via {@code ReadRelationships}
 *       (cached in-process for {@value #CACHE_TTL_MS} ms).</li>
 *   <li>Filters relationships whose {@code pattern} caveat-context field matches the incoming
 *       {@code METHOD PATH} string using a regex.</li>
 *   <li>Sorts matching rules by {@code priority} (descending; default 0).</li>
 *   <li>Evaluates the highest-priority rule's {@code policy_type}:
 *     <ul>
 *       <li>{@code "allow"} — returns allowed (with the {@code monitoring} flag).</li>
 *       <li>{@code "deny"} — returns denied (with the {@code monitoring} flag).</li>
 *       <li>{@code "ruleBased"} — checks required permissions then falls through to a
 *           {@code CheckBulkPermissions} call.</li>
 *     </ul>
 *   </li>
 *   <li>No match → returns denied with {@code monitoring=false}.</li>
 * </ol>
 */
class RouteSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(RouteSpiceDBQuery.class);

    private static final String RELATION_ENTITLED = "entitled";
    private static final String TYPE_USER = "frontegg_user";
    private static final String TYPE_TENANT = "frontegg_tenant";
    private static final String TYPE_ROUTE = "frontegg_route";
    private static final String REQUIRED_PERMISSION_RELATION = "required_permission";

    /** Cache TTL for route relationships, in milliseconds. */
    static final long CACHE_TTL_MS = 30_000L;

    private final ReadRelationshipsExecutor readRelationshipsExecutor;
    private final BulkPermissionsExecutor bulkExecutor;
    private final Supplier<Consistency> consistencySupplier;
    private final long cacheTtlMs;

    /**
     * A pre-compiled route rule: the pattern is compiled once when the cache is loaded
     * (H1 fix), avoiding repeated {@link Pattern#compile} calls on every request.
     */
    private record RouteRule(Pattern pattern, ReadRelationshipsResponse relationship) {}

    /**
     * Pairs a list of pre-compiled route rules with the timestamp at which they were fetched.
     * Using a single {@link AtomicReference} eliminates the unsafe publication race
     * that two separate {@code volatile} fields would have (non-atomic read/write of
     * the list + timestamp pair).
     *
     * <p>Note: on TTL expiry multiple concurrent threads may all observe a stale (or
     * {@code null}) cache entry and each fetch fresh data from SpiceDB simultaneously
     * (thundering herd). Given the 30 s TTL this is acceptable — the last writer wins
     * and subsequent calls will again hit the cache.
     */
    private record CachedRoutes(List<RouteRule> rules, long timestamp) {}
    private final AtomicReference<CachedRoutes> cache = new AtomicReference<>();

    RouteSpiceDBQuery(ReadRelationshipsExecutor readRelationshipsExecutor,
                      BulkPermissionsExecutor bulkExecutor,
                      Supplier<Consistency> consistencySupplier) {
        this(readRelationshipsExecutor, bulkExecutor, consistencySupplier, CACHE_TTL_MS);
    }

    RouteSpiceDBQuery(ReadRelationshipsExecutor readRelationshipsExecutor,
                      BulkPermissionsExecutor bulkExecutor,
                      Supplier<Consistency> consistencySupplier,
                      long cacheTtlMs) {
        this.readRelationshipsExecutor = readRelationshipsExecutor;
        this.bulkExecutor = bulkExecutor;
        this.consistencySupplier = consistencySupplier;
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * Executes a route entitlement check for the given user and route using the policy engine.
     *
     * @param userCtx  the user subject context (userId, tenantId, optional permissions/attributes)
     * @param routeCtx the route request context (HTTP method and path)
     * @return the entitlement result, including the {@code monitoring} flag
     */
    EntitlementsResult query(UserSubjectContext userCtx, RouteRequestContext routeCtx) {
        String target = routeCtx.method().toUpperCase() + " " + routeCtx.path();

        log.debug("Route policy check userId={} tenantId={} method={} path={}",
                userCtx.userId(), userCtx.tenantId(), routeCtx.method(), routeCtx.path());

        // 1. Load rules (cache or live fetch) — patterns are pre-compiled at load time (H1)
        List<RouteRule> rules = loadRelations();

        // 2. Filter by regex pattern match against "METHOD PATH"
        // .find() is used intentionally for partial/prefix matching, matching the JS SDK
        // behaviour. Changing to .matches() would diverge from JS parity (H2 documentation).
        List<RouteRule> matched = rules.stream()
                .filter(rule -> rule.pattern().matcher(target).find())
                .collect(Collectors.toList());

        // 3 + 4. Sort descending by priority
        matched.sort(Comparator.comparingDouble(
                (RouteRule rule) -> getNumberField(rule.relationship(), "priority", 0.0)
        ).reversed());

        // 5. No match → denied
        if (matched.isEmpty()) {
            log.debug("No matching route policy for {} — denied", target);
            return new EntitlementsResult(false, false);
        }

        // 6. Read monitoring from first (highest-priority) rule
        boolean monitoring = getBoolField(matched.get(0).relationship(), "monitoring", false);

        // 7/8/9. Evaluate policy_type of the first rule
        String policyType = getStringField(matched.get(0).relationship(), "policy_type", "deny");

        switch (policyType) {
            case "allow":
                log.debug("Route policy 'allow' matched for {} monitoring={}", target, monitoring);
                return new EntitlementsResult(true, monitoring);

            case "deny":
                log.debug("Route policy 'deny' matched for {} monitoring={}", target, monitoring);
                return new EntitlementsResult(false, monitoring);

            case "ruleBased":
                return evaluateRuleBased(matched, userCtx, routeCtx, monitoring, target);

            default:
                log.warn("Unknown policy_type '{}' for route {} — treating as deny", policyType, target);
                return new EntitlementsResult(false, monitoring);
        }
    }

    // -------------------------------------------------------------------------
    // Rule-based evaluation
    // -------------------------------------------------------------------------

    private EntitlementsResult evaluateRuleBased(
            List<RouteRule> matched,
            UserSubjectContext userCtx,
            RouteRequestContext routeCtx,
            boolean monitoring,
            String target) {

        // Filter to only ruleBased rules
        List<RouteRule> ruleBasedRules = matched.stream()
                .filter(rule -> "ruleBased".equals(getStringField(rule.relationship(), "policy_type", "deny")))
                .collect(Collectors.toList());

        // Build set of hashed user permissions
        Set<String> hashedUserPermissions = userCtx.permissions().stream()
                .map(Base64Utils::encode)
                .collect(Collectors.toSet());

        // For each rule whose relation contains "required_permission",
        // check that the subject ID is in the user's hashed permissions
        for (RouteRule rule : ruleBasedRules) {
            String relation = rule.relationship().getRelationship().getRelation();
            if (REQUIRED_PERMISSION_RELATION.equals(relation)) {
                String subjectId = rule.relationship().getRelationship().getSubject().getObject().getObjectId();
                if (!hashedUserPermissions.contains(subjectId)) {
                    log.debug("User {} missing required_permission subjectId={} for route {} — denied",
                            userCtx.userId(), subjectId, target);
                    return new EntitlementsResult(false, monitoring);
                }
            }
        }

        // Fall through to CheckBulkPermissions
        return checkBulkPermissions(userCtx, routeCtx, monitoring);
    }

    private EntitlementsResult checkBulkPermissions(
            UserSubjectContext userCtx,
            RouteRequestContext routeCtx,
            boolean monitoring) {

        String routeKey = routeCtx.method().toUpperCase() + ":" + routeCtx.path();
        String b64UserId = Base64Utils.encode(userCtx.userId());
        String b64TenantId = Base64Utils.encode(userCtx.tenantId());
        String b64RouteKey = Base64Utils.encode(routeKey);

        ObjectReference routeResource = ObjectReference.newBuilder()
                .setObjectType(TYPE_ROUTE)
                .setObjectId(b64RouteKey)
                .build();

        Struct caveatContext = CaveatContextBuilder.build(userCtx.attributes(), null);

        CheckBulkPermissionsRequestItem userItem = buildItem(
                TYPE_USER, b64UserId, routeResource, caveatContext);
        CheckBulkPermissionsRequestItem tenantItem = buildItem(
                TYPE_TENANT, b64TenantId, routeResource, caveatContext);

        CheckBulkPermissionsRequest request = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(consistencySupplier.get())
                .addItems(userItem)
                .addItems(tenantItem)
                .build();

        CheckBulkPermissionsResponse response = bulkExecutor.execute(request);

        for (CheckBulkPermissionsPair pair : response.getPairsList()) {
            if (pair.hasError()) {
                throw new EntitlementsQueryException(
                        "SpiceDB returned an error for route permission check: " + pair.getError().getMessage());
            }
        }

        boolean hasConditional = response.getPairsList().stream()
                .filter(CheckBulkPermissionsPair::hasItem)
                .map(pair -> pair.getItem().getPermissionship())
                .anyMatch(p -> p == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION);
        if (hasConditional) {
            log.warn("SpiceDB returned CONDITIONAL_PERMISSION for route check "
                            + "userId={} method={} path={} — treating as denied (fail-closed). "
                            + "Ensure caveat context is fully populated.",
                    userCtx.userId(), routeCtx.method(), routeCtx.path());
        }

        boolean entitled = response.getPairsList().stream()
                .filter(CheckBulkPermissionsPair::hasItem)
                .map(pair -> pair.getItem().getPermissionship())
                .anyMatch(p -> p == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);

        log.debug("Route bulk check result entitled={} userId={} method={} path={}",
                entitled, userCtx.userId(), routeCtx.method(), routeCtx.path());

        return new EntitlementsResult(entitled, monitoring);
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    /**
     * Loads route relationships from SpiceDB (or returns the cached list if still fresh).
     * Patterns are compiled once here — at cache-load time — rather than on every
     * {@link #query} call (H1 fix). Relationships with a null or invalid pattern are
     * skipped with a warning so a single bad entry does not break all route matching.
     */
    private List<RouteRule> loadRelations() {
        CachedRoutes current = cache.get();
        long now = System.currentTimeMillis();
        if (current != null && (now - current.timestamp()) < cacheTtlMs) {
            return current.rules();
        }

        ReadRelationshipsRequest request = ReadRelationshipsRequest.newBuilder()
                .setRelationshipFilter(RelationshipFilter.newBuilder()
                        .setResourceType(TYPE_ROUTE)
                        .build())
                .build();

        List<ReadRelationshipsResponse> relations = readRelationshipsExecutor.execute(request);

        // Pre-compile patterns once during cache load (H1).
        // Skip any relationship where the pattern field is missing or not a valid regex.
        List<RouteRule> rules = new ArrayList<>(relations.size());
        for (ReadRelationshipsResponse rel : relations) {
            String patternStr = getStringField(rel, "pattern", null);
            if (patternStr == null) {
                log.warn("Route relationship missing 'pattern' field — skipping");
                continue;
            }
            try {
                rules.add(new RouteRule(Pattern.compile(patternStr), rel));
            } catch (Exception e) {
                log.warn("Invalid regex pattern '{}' in route relationship — skipping", patternStr);
            }
        }

        cache.set(new CachedRoutes(rules, System.currentTimeMillis()));

        log.debug("Loaded {} route rules from SpiceDB ({} skipped due to missing/invalid pattern)",
                rules.size(), relations.size() - rules.size());
        return rules;
    }

    // -------------------------------------------------------------------------
    // Caveat field extraction helpers
    // -------------------------------------------------------------------------

    private String getStringField(ReadRelationshipsResponse rel, String fieldName, String defaultValue) {
        Value v = rel.getRelationship().getOptionalCaveat().getContext()
                .getFieldsOrDefault(fieldName, Value.getDefaultInstance());
        if (v.hasStringValue()) {
            return v.getStringValue();
        }
        return defaultValue;
    }

    private double getNumberField(ReadRelationshipsResponse rel, String fieldName, double defaultValue) {
        Value v = rel.getRelationship().getOptionalCaveat().getContext()
                .getFieldsOrDefault(fieldName, Value.getDefaultInstance());
        if (v.hasNumberValue()) {
            return v.getNumberValue();
        }
        return defaultValue;
    }

    private boolean getBoolField(ReadRelationshipsResponse rel, String fieldName, boolean defaultValue) {
        Value v = rel.getRelationship().getOptionalCaveat().getContext()
                .getFieldsOrDefault(fieldName, Value.getDefaultInstance());
        if (v.hasBoolValue()) {
            return v.getBoolValue();
        }
        return defaultValue;
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
