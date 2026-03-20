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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static final java.util.function.Supplier<com.authzed.api.v1.Consistency> TEST_CONSISTENCY =
            () -> com.authzed.api.v1.Consistency.newBuilder().setMinimizeLatency(true).build();

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                        subjectsResponse(base64(rawId2))), TEST_CONSISTENCY);

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                req -> iteratorOf(subjectsResponse(base64(rawId))), TEST_CONSISTENCY);

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
                }, TEST_CONSISTENCY);

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
    // lookupResources — caveat context (time-based access)
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_withAtTimestamp_requestIncludesCaveatContext() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        java.time.Instant at = java.time.Instant.parse("2026-03-01T00:00:00Z");
        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "user", "Tim", "read_doc", "document", at));

        LookupResourcesRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest);
        assertTrue(grpcRequest.hasContext(),
                "gRPC request must include caveat context when at is provided");
        assertEquals(at.toString(),
                grpcRequest.getContext().getFieldsOrThrow("at").getStringValue(),
                "caveat context 'at' field must contain ISO-8601 timestamp");
    }

    @Test
    void lookupResources_withNullAt_requestHasNoCaveatContext() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "user", "Tim", "read_doc", "document"));

        LookupResourcesRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest);
        assertFalse(grpcRequest.hasContext(),
                "gRPC request must not include caveat context when at is null");
    }

    // -------------------------------------------------------------------------
    // lookupSubjects — caveat context (time-based access)
    // -------------------------------------------------------------------------

    @Test
    void lookupSubjects_withAtTimestamp_requestIncludesCaveatContext() {
        AtomicReference<com.authzed.api.v1.LookupSubjectsRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                }, TEST_CONSISTENCY);

        java.time.Instant at = java.time.Instant.parse("2026-02-01T00:00:00Z");
        query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "document", "doc-1", "read_doc", "user", at));

        com.authzed.api.v1.LookupSubjectsRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest);
        assertTrue(grpcRequest.hasContext(),
                "gRPC request must include caveat context when at is provided");
        assertEquals(at.toString(),
                grpcRequest.getContext().getFieldsOrThrow("at").getStringValue(),
                "caveat context 'at' field must contain ISO-8601 timestamp");
    }

    @Test
    void lookupSubjects_withNullAt_requestHasNoCaveatContext() {
        AtomicReference<com.authzed.api.v1.LookupSubjectsRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                }, TEST_CONSISTENCY);

        query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "document", "doc-1", "read_doc", "user"));

        com.authzed.api.v1.LookupSubjectsRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest);
        assertFalse(grpcRequest.hasContext(),
                "gRPC request must not include caveat context when at is null");
    }

    // -------------------------------------------------------------------------
    // Base64 roundtrip — encode then decode produces original value
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_base64Roundtrip_unicodeId_encodedAndDecodedCorrectly() {
        String rawId = "用户-001";

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(resourcesResponse(base64(rawId))),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                req -> iteratorOf(subjectsResponse(base64(rawId))), TEST_CONSISTENCY);

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
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

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
                failingExecutor, TEST_CONSISTENCY);

        assertThrows(StatusRuntimeException.class, () -> query.lookupSubjects(
                new com.frontegg.sdk.entitlements.model.LookupSubjectsRequest(
                        "document", "doc-123", "viewer", "frontegg_user")));
    }

    // -------------------------------------------------------------------------
    // Null parameter handling
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_nullRequest_throwsNullPointerException() {
        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        assertThrows(NullPointerException.class, () -> query.lookupResources(null));
    }

    @Test
    void lookupSubjects_nullRequest_throwsNullPointerException() {
        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> Collections.emptyIterator(),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        assertThrows(NullPointerException.class, () -> query.lookupSubjects(null));
    }

    // -------------------------------------------------------------------------
    // lookupResources — pagination (limit and cursor)
    // -------------------------------------------------------------------------

    @Test
    void lookupResources_withLimit_setsLimitOnRequest() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature",
                        null, 10, null));

        LookupResourcesRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest, "gRPC request must have been captured");
        assertEquals(10, grpcRequest.getOptionalLimit(),
                "optionalLimit must match the limit supplied in the SDK request");
    }

    @Test
    void lookupResources_withCursor_setCursorOnRequest() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature",
                        null, null, "page-token-abc"));

        LookupResourcesRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest, "gRPC request must have been captured");
        assertTrue(grpcRequest.hasOptionalCursor(),
                "gRPC request must include optionalCursor when cursor is set");
        assertEquals("page-token-abc", grpcRequest.getOptionalCursor().getToken(),
                "optionalCursor token must match the cursor supplied in the SDK request");
    }

    @Test
    void lookupResources_resultsEqualLimit_returnsCursor() {
        String rawId1 = "res-1";
        String rawId2 = "res-2";
        String cursorToken = "next-page-token";

        com.authzed.api.v1.Cursor afterCursor =
                com.authzed.api.v1.Cursor.newBuilder().setToken(cursorToken).build();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(
                        resourcesResponseWithCursor(base64(rawId1), afterCursor),
                        resourcesResponseWithCursor(base64(rawId2), afterCursor)),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        // limit=2, responses=2  →  should return nextCursor
        com.frontegg.sdk.entitlements.model.LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature",
                        null, 2, null));

        assertEquals(2, result.entityIds().size());
        assertEquals(cursorToken, result.nextCursor(),
                "nextCursor must be the last response's afterResultCursor token when result count == limit");
    }

    @Test
    void lookupResources_resultsLessThanLimit_returnsNullCursor() {
        String rawId = "only-result";
        com.authzed.api.v1.Cursor afterCursor =
                com.authzed.api.v1.Cursor.newBuilder().setToken("should-not-appear").build();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(resourcesResponseWithCursor(base64(rawId), afterCursor)),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        // limit=5, responses=1  →  nextCursor must be null
        com.frontegg.sdk.entitlements.model.LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature",
                        null, 5, null));

        assertEquals(1, result.entityIds().size());
        assertNull(result.nextCursor(),
                "nextCursor must be null when result count is less than the limit");
    }

    @Test
    void lookupResources_fewerResultsThanLimit_returnsNullNextCursor() {
        String rawId = "single-resource";
        com.authzed.api.v1.Cursor cursor =
                com.authzed.api.v1.Cursor.newBuilder().setToken("unused-token").build();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(resourcesResponseWithCursor(base64(rawId), cursor)),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        // limit=2, results=1  →  nextCursor must be null
        com.frontegg.sdk.entitlements.model.LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature",
                        null, 2, null));

        assertEquals(1, result.entityIds().size());
        assertNull(result.nextCursor(),
                "nextCursor must be null when fewer results are returned than the limit");
    }

    @Test
    void lookupResources_resultsEqualLimitButNoAfterCursor_returnsNullNextCursor() {
        String rawId1 = "res-1";
        String rawId2 = "res-2";

        // Build responses without afterResultCursor set (no cursor in the last response)
        LookupResourcesResponse resp1 = LookupResourcesResponse.newBuilder()
                .setResourceObjectId(base64(rawId1))
                .build();
        LookupResourcesResponse resp2 = LookupResourcesResponse.newBuilder()
                .setResourceObjectId(base64(rawId2))
                .build();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> iteratorOf(resp1, resp2),
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        // limit=2, results=2 but last response has NO afterResultCursor  →  nextCursor must be null
        com.frontegg.sdk.entitlements.model.LookupResult result = query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature",
                        null, 2, null));

        assertEquals(2, result.entityIds().size());
        assertNull(result.nextCursor(),
                "nextCursor must be null when result count equals limit but last response has no afterResultCursor");
    }

    @Test
    void lookupResources_defaultLimit_usedWhenLimitIsNull() {
        AtomicReference<LookupResourcesRequest> captured = new AtomicReference<>();

        LookupSpiceDBQuery query = new LookupSpiceDBQuery(
                req -> {
                    captured.set(req);
                    return Collections.emptyIterator();
                },
                req -> Collections.emptyIterator(), TEST_CONSISTENCY);

        // null limit  →  DEFAULT_LOOKUP_LIMIT (50) must be applied
        query.lookupResources(
                new com.frontegg.sdk.entitlements.model.LookupResourcesRequest(
                        "frontegg_user", "user-1", "entitled", "frontegg_feature"));

        LookupResourcesRequest grpcRequest = captured.get();
        assertNotNull(grpcRequest, "gRPC request must have been captured");
        assertEquals(LookupSpiceDBQuery.DEFAULT_LOOKUP_LIMIT, grpcRequest.getOptionalLimit(),
                "optionalLimit must equal DEFAULT_LOOKUP_LIMIT when request.limit() is null");
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    private static LookupResourcesResponse resourcesResponse(String base64Id) {
        return LookupResourcesResponse.newBuilder()
                .setResourceObjectId(base64Id)
                .build();
    }

    private static LookupResourcesResponse resourcesResponseWithCursor(
            String base64Id, com.authzed.api.v1.Cursor afterCursor) {
        return LookupResourcesResponse.newBuilder()
                .setResourceObjectId(base64Id)
                .setAfterResultCursor(afterCursor)
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
