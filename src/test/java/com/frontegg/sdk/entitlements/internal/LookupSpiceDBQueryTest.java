package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.LookupResourcesRequest;
import com.authzed.api.v1.LookupResourcesResponse;
import com.authzed.api.v1.LookupSubjectsResponse;
import com.authzed.api.v1.ResolvedSubject;
import com.frontegg.sdk.entitlements.model.LookupResult;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LookupSpiceDBQuery}.
 *
 * <p>Uses hand-crafted {@link LookupResourcesExecutor} and {@link LookupSubjectsExecutor}
 * lambdas instead of Mockito mocks, consistent with the project's existing test strategy
 * (see {@code FgaSpiceDBQueryTest}).
 */
class LookupSpiceDBQueryTest {

    // -------------------------------------------------------------------------
    // lookupResources — result decoding
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_multipleResults_returnsDecodedResourceIds() {
        String rawId1 = "feature-flag-alpha";
        String rawId2 = "feature-flag-beta";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(
                        resourcesResponse(base64(rawId1)),
                        resourcesResponse(base64(rawId2))),
                req -> Collections.emptyIterator());

        LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-123", "entitled", "frontegg_feature"));

        assertNotNull(result);
        assertEquals(2, result.entityIds().size());
        assertEquals(rawId1, result.entityIds().get(0));
        assertEquals(rawId2, result.entityIds().get(1));
    }

    @Test
    void lookupResources_emptyResult_returnsEmptyList() {
        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> Collections.emptyIterator());

        LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-no-access", "entitled", "frontegg_feature"));

        assertNotNull(result);
        assertTrue(result.entityIds().isEmpty(), "empty iterator must produce empty result");
    }

    @Test
    void lookupResources_singleResult_returnsDecodedId() {
        String rawId = "my-feature";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(resourcesResponse(base64(rawId))),
                req -> Collections.emptyIterator());

        LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "service_account", "svc-01", "viewer", "document"));

        assertEquals(List.of(rawId), result.entityIds());
    }

    // -------------------------------------------------------------------------
    // lookupResources — request construction
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_requestConstruction_subjectTypeAndIdAreEncoded() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator());

        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-abc@example.com", "entitled", "frontegg_feature"));

        LookupResourcesRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest, "gRPC request must have been captured");

        assertEquals("frontegg_user",
                grpcRequest.getSubject().getObject().getObjectType(),
                "subject object type must match subjectType");
        assertEquals(base64("user-abc@example.com"),
                grpcRequest.getSubject().getObject().getObjectId(),
                "subject object id must be base64-encoded subjectId");
        assertEquals("frontegg_feature",
                grpcRequest.getResourceObjectType(),
                "resource object type must match resourceType");
        assertEquals("entitled",
                grpcRequest.getPermission(),
                "permission must match the request permission");
    }

    @Test
    void lookupResources_subjectIdBase64_isUrlSafeNoPadding() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator());

        // Use an ID that would produce standard Base64 padding
        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "user", "id-1", "read", "doc"));

        String encodedId = captured.get().getSubject().getObject().getObjectId();
        assertFalse_contains(encodedId, "=", "URL-safe no-padding Base64 must not contain '='");
        assertFalse_contains(encodedId, "+", "URL-safe no-padding Base64 must not contain '+'");
    }

    // -------------------------------------------------------------------------
    // lookupSubjects — result decoding
    // -------------------------------------------------------------------------

    @Test
    void lookupSubjects_multipleResults_returnsDecodedSubjectIds() {
        String rawId1 = "user-alice";
        String rawId2 = "user-bob";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> iteratorOf(
                        subjectsResponse(base64(rawId1)),
                        subjectsResponse(base64(rawId2))));

        LookupResult result = query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "document", "doc-123", "viewer", "frontegg_user"));

        assertNotNull(result);
        assertEquals(2, result.entityIds().size());
        assertEquals(rawId1, result.entityIds().get(0));
        assertEquals(rawId2, result.entityIds().get(1));
    }

    @Test
    void lookupSubjects_emptyResult_returnsEmptyList() {
        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> Collections.emptyIterator());

        LookupResult result = query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "project", "proj-no-viewers", "viewer", "frontegg_user"));

        assertNotNull(result);
        assertTrue(result.entityIds().isEmpty(), "empty iterator must produce empty result");
    }

    @Test
    void lookupSubjects_singleResult_returnsDecodedId() {
        String rawId = "user-sole-owner";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> iteratorOf(subjectsResponse(base64(rawId))));

        LookupResult result = query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "workspace", "ws-42", "owner", "frontegg_user"));

        assertEquals(List.of(rawId), result.entityIds());
    }

    // -------------------------------------------------------------------------
    // lookupSubjects — request construction
    // -------------------------------------------------------------------------

    @Test
    void lookupSubjects_requestConstruction_resourceTypeAndIdAreEncoded() {
        AtomicReference<com.authzed.api.v1.LookupSubjectsRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                });

        query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "document", "doc/path/file.txt", "viewer", "frontegg_user"));

        com.authzed.api.v1.LookupSubjectsRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest, "gRPC request must have been captured");

        assertEquals("document",
                grpcRequest.getResource().getObjectType(),
                "resource object type must match resourceType");
        assertEquals(base64("doc/path/file.txt"),
                grpcRequest.getResource().getObjectId(),
                "resource object id must be base64-encoded resourceId");
        assertEquals("frontegg_user",
                grpcRequest.getSubjectObjectType(),
                "subject object type must match subjectType");
        assertEquals("viewer",
                grpcRequest.getPermission(),
                "permission must match the request permission");
    }

    // -------------------------------------------------------------------------
    // Base64 roundtrip — encode then decode produces original value
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_base64Roundtrip_unicodeId_encodedAndDecodedCorrectly() {
        String rawId = "用户-001";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(resourcesResponse(base64(rawId))),
                req -> Collections.emptyIterator());

        LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "user", "subj-1", "read", "doc"));

        assertEquals(List.of(rawId), result.entityIds(),
                "Unicode resource ID must survive base64 encode/decode roundtrip");
    }

    @Test
    void lookupSubjects_base64Roundtrip_specialCharsId_encodedAndDecodedCorrectly() {
        String rawId = "user@example.com+tenant/org";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> iteratorOf(subjectsResponse(base64(rawId))));

        LookupResult result = query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "repo", "repo-1", "admin", "user"));

        assertEquals(List.of(rawId), result.entityIds(),
                "Special-char subject ID must survive base64 encode/decode roundtrip");
    }

    // -------------------------------------------------------------------------
    // Error path — executor throws
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_executorThrows_propagatesException() {
        LookupResourcesExecutor failingExecutor = request -> {
            throw new StatusRuntimeException(Status.UNAVAILABLE);
        };
        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                failingExecutor,
                req -> Collections.emptyIterator());

        assertThrows(StatusRuntimeException.class, () -> query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user1", "entitled", "frontegg_feature")));
    }

    @Test
    void lookupSubjects_executorThrows_propagatesException() {
        LookupSubjectsExecutor failingExecutor = request -> {
            throw new StatusRuntimeException(Status.UNAVAILABLE);
        };
        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                failingExecutor);

        assertThrows(StatusRuntimeException.class, () -> query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "document", "doc-123", "viewer", "frontegg_user")));
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    private static LookupResourcesResponse resourcesResponse(String base64Id) {
        return LookupResourcesResponse.newBuilder()
                .setResourceObjectId(base64Id)
                .build();
    }

    private static LookupSubjectsResponse subjectsResponse(String base64SubjectId) {
        return LookupSubjectsResponse.newBuilder()
                .setSubject(ResolvedSubject.newBuilder()
                        .setSubjectObjectId(base64SubjectId)
                        .build())
                .build();
    }

    @SafeVarargs
    private static <T> Iterator<T> iteratorOf(T... items) {
        return List.of(items).iterator();
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertFalse_contains(String value, String substring, String message) {
        if (value.contains(substring)) {
            throw new AssertionError(message + " — value was: " + value);
        }
    }
}
