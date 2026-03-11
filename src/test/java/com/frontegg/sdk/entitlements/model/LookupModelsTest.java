package com.frontegg.sdk.entitlements.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the lookup model records introduced in Story 4.2/4.3:
 * {@link LookupResourcesRequest}, {@link LookupSubjectsRequest}, and {@link LookupResult}.
 */
class LookupModelsTest {

    // =========================================================================
    // LookupResourcesRequest
    // =========================================================================

    @Nested
    class LookupResourcesRequestTests {

        @Test
        void nullSubjectType_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupResourcesRequest(null, "user-1", "entitled", "feature"));
        }

        @Test
        void nullSubjectId_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupResourcesRequest("user", null, "entitled", "feature"));
        }

        @Test
        void nullPermission_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupResourcesRequest("user", "user-1", null, "feature"));
        }

        @Test
        void nullResourceType_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupResourcesRequest("user", "user-1", "entitled", null));
        }

        @Test
        void validConstruction_allFieldsSet() {
            LookupResourcesRequest req = new LookupResourcesRequest(
                    "frontegg_user", "user-abc", "entitled", "frontegg_feature");

            assertEquals("frontegg_user", req.subjectType());
            assertEquals("user-abc", req.subjectId());
            assertEquals("entitled", req.permission());
            assertEquals("frontegg_feature", req.resourceType());
        }

        @Test
        void validConstruction_emptyStringValues_areAllowed() {
            // Empty strings pass the null check — callers are responsible for meaningful values
            LookupResourcesRequest req = new LookupResourcesRequest("", "", "", "");

            assertEquals("", req.subjectType());
            assertEquals("", req.subjectId());
        }

        @Test
        void recordEquality_sameValues_areEqual() {
            LookupResourcesRequest r1 = new LookupResourcesRequest("u", "1", "p", "r");
            LookupResourcesRequest r2 = new LookupResourcesRequest("u", "1", "p", "r");

            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }
    }

    // =========================================================================
    // LookupSubjectsRequest
    // =========================================================================

    @Nested
    class LookupSubjectsRequestTests {

        @Test
        void nullResourceType_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupSubjectsRequest(null, "doc-1", "viewer", "user"));
        }

        @Test
        void nullResourceId_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupSubjectsRequest("document", null, "viewer", "user"));
        }

        @Test
        void nullPermission_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupSubjectsRequest("document", "doc-1", null, "user"));
        }

        @Test
        void nullSubjectType_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new LookupSubjectsRequest("document", "doc-1", "viewer", null));
        }

        @Test
        void validConstruction_allFieldsSet() {
            LookupSubjectsRequest req = new LookupSubjectsRequest(
                    "document", "doc-789", "viewer", "frontegg_user");

            assertEquals("document", req.resourceType());
            assertEquals("doc-789", req.resourceId());
            assertEquals("viewer", req.permission());
            assertEquals("frontegg_user", req.subjectType());
        }

        @Test
        void recordEquality_sameValues_areEqual() {
            LookupSubjectsRequest r1 = new LookupSubjectsRequest("d", "1", "v", "u");
            LookupSubjectsRequest r2 = new LookupSubjectsRequest("d", "1", "v", "u");

            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }
    }

    // =========================================================================
    // LookupResult
    // =========================================================================

    @Nested
    class LookupResultTests {

        @Test
        void nullEntityIds_throwsNullPointerException() {
            assertThrows(NullPointerException.class, () -> new LookupResult(null));
        }

        @Test
        void validConstruction_emptyList() {
            LookupResult result = new LookupResult(List.of());

            assertTrue(result.entityIds().isEmpty());
        }

        @Test
        void validConstruction_nonEmptyList() {
            LookupResult result = new LookupResult(List.of("id-1", "id-2", "id-3"));

            assertEquals(3, result.entityIds().size());
            assertEquals("id-1", result.entityIds().get(0));
            assertEquals("id-2", result.entityIds().get(1));
            assertEquals("id-3", result.entityIds().get(2));
        }

        @Test
        void entityIds_defensiveCopy_originalListModificationDoesNotAffectRecord() {
            List<String> original = new ArrayList<>();
            original.add("id-1");
            original.add("id-2");

            LookupResult result = new LookupResult(original);

            original.add("id-3");
            original.set(0, "modified");

            assertEquals(2, result.entityIds().size(),
                    "Record must not reflect additions to the original list");
            assertEquals("id-1", result.entityIds().get(0),
                    "Record must not reflect mutations to the original list");
        }

        @Test
        void entityIds_isImmutable_attemptToModifyThrowsException() {
            LookupResult result = new LookupResult(List.of("id-1", "id-2"));

            assertThrows(UnsupportedOperationException.class,
                    () -> result.entityIds().add("id-3"),
                    "entityIds list must be unmodifiable");
            assertThrows(UnsupportedOperationException.class,
                    () -> result.entityIds().set(0, "modified"),
                    "entityIds list must be unmodifiable");
        }

        @Test
        void empty_factory_returnsEmptyEntityIdsList() {
            LookupResult empty = LookupResult.empty();

            assertNotSame(null, empty, "empty() must not return null");
            assertTrue(empty.entityIds().isEmpty(), "empty() must return an empty list");
        }

        @Test
        void empty_factory_returnedListIsImmutable() {
            LookupResult empty = LookupResult.empty();

            assertThrows(UnsupportedOperationException.class,
                    () -> empty.entityIds().add("id"),
                    "List from empty() must be unmodifiable");
        }

        @Test
        void recordEquality_sameValues_areEqual() {
            LookupResult r1 = new LookupResult(List.of("a", "b"));
            LookupResult r2 = new LookupResult(List.of("a", "b"));

            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }

        @Test
        void empty_factory_multipleCallsProduceEqualResults() {
            LookupResult e1 = LookupResult.empty();
            LookupResult e2 = LookupResult.empty();

            assertEquals(e1, e2, "multiple calls to empty() must produce equal results");
        }
    }
}
