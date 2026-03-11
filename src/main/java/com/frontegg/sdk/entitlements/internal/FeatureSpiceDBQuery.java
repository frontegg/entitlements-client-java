package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private query strategy that handles {@link FeatureRequestContext} checks.
 *
 * <p>Sends a {@code CheckBulkPermissions} request with two items:
 * <ol>
 *   <li>subject {@code frontegg_user:<base64(userId)>} → resource
 *       {@code frontegg_feature:<base64(featureKey)>}, relation {@code entitled}</li>
 *   <li>subject {@code frontegg_tenant:<base64(tenantId)>} → resource
 *       {@code frontegg_feature:<base64(featureKey)>}, relation {@code entitled}</li>
 * </ol>
 *
 * <p>Returns {@link EntitlementsResult#allowed()} if <em>any</em> pair comes back with
 * {@code PERMISSIONSHIP_HAS_PERMISSION}; otherwise returns {@link EntitlementsResult#denied()}.
 *
 * <p>The gRPC call is performed via the injected {@link BulkPermissionsExecutor}, which
 * allows tests to provide a simple lambda without needing to mock the final blocking stub.
 */
class FeatureSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(FeatureSpiceDBQuery.class);

    private static final String RELATION_ENTITLED = "entitled";
    private static final String TYPE_USER = "frontegg_user";
    private static final String TYPE_TENANT = "frontegg_tenant";
    private static final String TYPE_FEATURE = "frontegg_feature";

    private final BulkPermissionsExecutor executor;

    FeatureSpiceDBQuery(BulkPermissionsExecutor executor) {
        this.executor = executor;
    }

    /**
     * Executes a feature entitlement check for the given user and feature.
     *
     * @param userCtx    the user subject context (userId, tenantId, optional attributes)
     * @param featureCtx the feature request context (featureKey)
     * @return {@link EntitlementsResult#allowed()} if the user or tenant is entitled;
     *         {@link EntitlementsResult#denied()} otherwise
     */
    EntitlementsResult query(UserSubjectContext userCtx, FeatureRequestContext featureCtx) {
        String b64UserId = Base64Utils.encode(userCtx.userId());
        String b64TenantId = Base64Utils.encode(userCtx.tenantId());
        String b64FeatureKey = Base64Utils.encode(featureCtx.featureKey());

        log.debug("Feature check userId={} tenantId={} featureKey={}",
                userCtx.userId(), userCtx.tenantId(), featureCtx.featureKey());

        ObjectReference featureResource = ObjectReference.newBuilder()
                .setObjectType(TYPE_FEATURE)
                .setObjectId(b64FeatureKey)
                .build();

        Struct caveatContext = CaveatContextBuilder.build(userCtx.attributes(), featureCtx.at());

        CheckBulkPermissionsRequestItem userItem = buildItem(
                TYPE_USER, b64UserId, featureResource, caveatContext);
        CheckBulkPermissionsRequestItem tenantItem = buildItem(
                TYPE_TENANT, b64TenantId, featureResource, caveatContext);

        CheckBulkPermissionsRequest request = CheckBulkPermissionsRequest.newBuilder()
                .addItems(userItem)
                .addItems(tenantItem)
                .build();

        CheckBulkPermissionsResponse response = executor.execute(request);

        for (CheckBulkPermissionsPair pair : response.getPairsList()) {
            if (pair.hasError()) {
                throw new EntitlementsQueryException(
                        "SpiceDB returned an error for bulk permission check: " + pair.getError().getMessage());
            }
        }

        boolean entitled = response.getPairsList().stream()
                .filter(CheckBulkPermissionsPair::hasItem)
                .map(pair -> pair.getItem().getPermissionship())
                .anyMatch(p -> p == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);

        log.debug("Feature check result entitled={} userId={} featureKey={}",
                entitled, userCtx.userId(), featureCtx.featureKey());

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
