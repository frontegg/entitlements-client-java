package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.LookupResourcesResponse;
import com.authzed.api.v1.LookupSubjectsResponse;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.google.protobuf.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Package-private query strategy that handles SpiceDB {@code LookupResources} and
 * {@code LookupSubjects} RPCs.
 *
 * <p>Both methods encode the caller-supplied IDs with URL-safe Base64 (matching the TypeScript
 * SDK's {@code normalizeObjectId}) before sending the gRPC request, and decode the returned
 * IDs back to plain strings before returning them to the caller.
 *
 * <p>gRPC calls are performed via the injected {@link LookupResourcesExecutor} and
 * {@link LookupSubjectsExecutor} functional interfaces, which allows tests to provide
 * simple lambdas without needing to mock the final blocking stub.
 */
class LookupSpiceDBQuery {

    private static final Logger log = LoggerFactory.getLogger(LookupSpiceDBQuery.class);

    private final LookupResourcesExecutor lookupResourcesExecutor;
    private final LookupSubjectsExecutor lookupSubjectsExecutor;

    LookupSpiceDBQuery(LookupResourcesExecutor lookupResourcesExecutor,
                       LookupSubjectsExecutor lookupSubjectsExecutor) {
        this.lookupResourcesExecutor = lookupResourcesExecutor;
        this.lookupSubjectsExecutor = lookupSubjectsExecutor;
    }

    /**
     * Looks up all resources of the given type that the subject has the specified permission on.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link LookupResult} with the decoded resource IDs
     */
    LookupResult lookupResources(LookupResourcesRequest request) {
        String b64SubjectId = Base64Utils.encode(request.subjectId());

        log.debug("LookupResources subjectType={} subjectId={} permission={} resourceType={}",
                request.subjectType(), request.subjectId(), request.permission(),
                request.resourceType());

        Struct caveatContext = CaveatContextBuilder.build(null, request.at());

        com.authzed.api.v1.LookupResourcesRequest.Builder grpcRequestBuilder =
                com.authzed.api.v1.LookupResourcesRequest.newBuilder()
                        .setConsistency(Consistency.newBuilder().setFullyConsistent(true).build())
                        .setSubject(SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(request.subjectType())
                                        .setObjectId(b64SubjectId)
                                        .build())
                                .build())
                        .setResourceObjectType(request.resourceType())
                        .setPermission(request.permission());

        if (caveatContext != null) {
            grpcRequestBuilder.setContext(caveatContext);
        }

        com.authzed.api.v1.LookupResourcesRequest grpcRequest = grpcRequestBuilder.build();

        Iterator<LookupResourcesResponse> responseIterator =
                lookupResourcesExecutor.execute(grpcRequest);

        List<String> resourceIds = new ArrayList<>();
        while (responseIterator.hasNext()) {
            LookupResourcesResponse resp = responseIterator.next();
            resourceIds.add(Base64Utils.decode(resp.getResourceObjectId()));
        }

        log.debug("LookupResources found {} resources subjectType={} subjectId={}",
                resourceIds.size(), request.subjectType(), request.subjectId());

        return new LookupResult(resourceIds);
    }

    /**
     * Looks up all subjects of the given type that have the specified permission on a resource.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link LookupResult} with the decoded subject IDs
     */
    LookupResult lookupSubjects(LookupSubjectsRequest request) {
        String b64ResourceId = Base64Utils.encode(request.resourceId());

        log.debug("LookupSubjects resourceType={} resourceId={} permission={} subjectType={}",
                request.resourceType(), request.resourceId(), request.permission(),
                request.subjectType());

        Struct caveatContext = CaveatContextBuilder.build(null, request.at());

        com.authzed.api.v1.LookupSubjectsRequest.Builder grpcRequestBuilder =
                com.authzed.api.v1.LookupSubjectsRequest.newBuilder()
                        .setConsistency(Consistency.newBuilder().setFullyConsistent(true).build())
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(request.resourceType())
                                .setObjectId(b64ResourceId)
                                .build())
                        .setPermission(request.permission())
                        .setSubjectObjectType(request.subjectType());

        if (caveatContext != null) {
            grpcRequestBuilder.setContext(caveatContext);
        }

        com.authzed.api.v1.LookupSubjectsRequest grpcRequest = grpcRequestBuilder.build();

        Iterator<LookupSubjectsResponse> responseIterator =
                lookupSubjectsExecutor.execute(grpcRequest);

        List<String> subjectIds = new ArrayList<>();
        while (responseIterator.hasNext()) {
            LookupSubjectsResponse resp = responseIterator.next();
            subjectIds.add(Base64Utils.decode(resp.getSubject().getSubjectObjectId()));
        }

        log.debug("LookupSubjects found {} subjects resourceType={} resourceId={}",
                subjectIds.size(), request.resourceType(), request.resourceId());

        return new LookupResult(subjectIds);
    }
}
