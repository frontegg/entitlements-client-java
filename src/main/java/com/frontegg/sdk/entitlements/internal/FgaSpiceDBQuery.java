package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckPermissionRequest;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private query strategy that handles fine-grained authorization (FGA) checks
 * for {@link EntityRequestContext} requests.
 *
 * <p>Sends a single {@code CheckPermission} request to SpiceDB:
 * <ul>
 *   <li>subject: {@code <entityType>:<base64(entityId)>}</li>
 *   <li>resource: {@code <resourceType>:<base64(resourceId)>}</li>
 *   <li>permission: the {@code relation} from the request context</li>
 * </ul>
 *
 * <p>Returns {@link EntitlementsResult#allowed()} if the response is
 * {@code PERMISSIONSHIP_HAS_PERMISSION}; otherwise returns
 * {@link EntitlementsResult#denied()}.
 *
 * <p>The gRPC call is performed via the injected {@link CheckPermissionExecutor}, which
 * allows tests to provide a simple lambda without needing to mock the final blocking stub.
 */
class FgaSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(FgaSpiceDBQuery.class);

    private final CheckPermissionExecutor executor;

    FgaSpiceDBQuery(CheckPermissionExecutor executor) {
        this.executor = executor;
    }

    /**
     * Executes an FGA entitlement check between the given entity subject and resource.
     *
     * @param entityCtx  the entity subject context (entityType, entityId)
     * @param requestCtx the entity request context (resourceType, resourceId, relation)
     * @return {@link EntitlementsResult#allowed()} if the entity has the specified relation
     *         on the resource; {@link EntitlementsResult#denied()} otherwise
     */
    EntitlementsResult query(EntitySubjectContext entityCtx, EntityRequestContext requestCtx) {
        String b64EntityId = Base64Utils.encode(entityCtx.entityId());
        String b64ResourceId = Base64Utils.encode(requestCtx.resourceId());

        log.debug("FGA check entityType={} entityId={} resourceType={} resourceId={} relation={}",
                entityCtx.entityType(), entityCtx.entityId(),
                requestCtx.resourceType(), requestCtx.resourceId(),
                requestCtx.relation());

        SubjectReference subject = SubjectReference.newBuilder()
                .setObject(ObjectReference.newBuilder()
                        .setObjectType(entityCtx.entityType())
                        .setObjectId(b64EntityId)
                        .build())
                .build();

        ObjectReference resource = ObjectReference.newBuilder()
                .setObjectType(requestCtx.resourceType())
                .setObjectId(b64ResourceId)
                .build();

        Struct caveatContext = CaveatContextBuilder.build(null, requestCtx.at());

        CheckPermissionRequest.Builder requestBuilder = CheckPermissionRequest.newBuilder()
                .setConsistency(Consistency.newBuilder()
                        .setFullyConsistent(true)
                        .build())
                .setSubject(subject)
                .setResource(resource)
                .setPermission(requestCtx.relation());

        if (caveatContext != null) {
            requestBuilder.setContext(caveatContext);
        }

        CheckPermissionRequest request = requestBuilder.build();

        CheckPermissionResponse response = executor.execute(request);

        boolean allowed = response.getPermissionship()
                == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION
                || response.getPermissionship()
                == CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION;

        log.debug("FGA check result allowed={} entityType={} entityId={} resourceType={} resourceId={} relation={}",
                allowed,
                entityCtx.entityType(), entityCtx.entityId(),
                requestCtx.resourceType(), requestCtx.resourceId(),
                requestCtx.relation());

        return allowed ? EntitlementsResult.allowed() : EntitlementsResult.denied();
    }
}
