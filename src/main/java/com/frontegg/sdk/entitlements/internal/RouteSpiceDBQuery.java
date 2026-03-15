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
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Package-private query strategy that handles {@link RouteRequestContext} checks.
 *
 * <p>Sends a {@code CheckBulkPermissions} request with two items:
 * <ol>
 *   <li>subject {@code frontegg_user:<base64(userId)>} → resource
 *       {@code frontegg_route:<base64(METHOD:PATH)>}, relation {@code entitled}</li>
 *   <li>subject {@code frontegg_tenant:<base64(tenantId)>} → resource
 *       {@code frontegg_route:<base64(METHOD:PATH)>}, relation {@code entitled}</li>
 * </ol>
 *
 * <p>Returns {@link EntitlementsResult#allowed()} if <em>any</em> pair comes back with
 * {@code PERMISSIONSHIP_HAS_PERMISSION}; otherwise returns {@link EntitlementsResult#denied()}.
 *
 * <p>The route key is constructed as {@code METHOD:PATH} (e.g. {@code "GET:/api/v1/reports"})
 * and then base64-encoded as the resource object ID.
 *
 * <p>The gRPC call is performed via the injected {@link BulkPermissionsExecutor}, which
 * allows tests to provide a simple lambda without needing to mock the final blocking stub.
 */
class RouteSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(RouteSpiceDBQuery.class);

    private static final String RELATION_ENTITLED = "entitled";
    private static final String TYPE_USER = "frontegg_user";
    private static final String TYPE_TENANT = "frontegg_tenant";
    private static final String TYPE_ROUTE = "frontegg_route";

    private final BulkPermissionsExecutor executor;
    private final Supplier<Consistency> consistencySupplier;

    RouteSpiceDBQuery(BulkPermissionsExecutor executor, Supplier<Consistency> consistencySupplier) {
        this.executor = executor;
        this.consistencySupplier = consistencySupplier;
    }

    /**
     * Executes a route entitlement check for the given user and route.
     *
     * @param userCtx  the user subject context (userId, tenantId, optional attributes)
     * @param routeCtx the route request context (HTTP method and path)
     * @return {@link EntitlementsResult#allowed()} if the user or tenant is entitled;
     *         {@link EntitlementsResult#denied()} otherwise
     */
    EntitlementsResult query(UserSubjectContext userCtx, RouteRequestContext routeCtx) {
        // Route key format: "METHOD:PATH"
        String routeKey = routeCtx.method().toUpperCase() + ":" + routeCtx.path();
        String b64UserId = Base64Utils.encode(userCtx.userId());
        String b64TenantId = Base64Utils.encode(userCtx.tenantId());
        String b64RouteKey = Base64Utils.encode(routeKey);

        log.debug("Route check userId={} tenantId={} method={} path={}",
                userCtx.userId(), userCtx.tenantId(), routeCtx.method(), routeCtx.path());

        ObjectReference routeResource = ObjectReference.newBuilder()
                .setObjectType(TYPE_ROUTE)
                .setObjectId(b64RouteKey)
                .build();

        Struct caveatContext = CaveatContextBuilder.build(userCtx.attributes(), routeCtx.at());

        CheckBulkPermissionsRequestItem userItem = buildItem(
                TYPE_USER, b64UserId, routeResource, caveatContext);
        CheckBulkPermissionsRequestItem tenantItem = buildItem(
                TYPE_TENANT, b64TenantId, routeResource, caveatContext);

        CheckBulkPermissionsRequest request = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(consistencySupplier.get())
                .addItems(userItem)
                .addItems(tenantItem)
                .build();

        CheckBulkPermissionsResponse response = executor.execute(request);

        // Check for errors first
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

        log.debug("Route check result entitled={} userId={} method={} path={}",
                entitled, userCtx.userId(), routeCtx.method(), routeCtx.path());

        return entitled ? EntitlementsResult.allowed() : EntitlementsResult.denied();
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
