package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckPermissionRequest;
import com.authzed.api.v1.CheckPermissionResponse;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FgaSpiceDBQuery}.
 *
 * <p>Uses a hand-crafted {@link CheckPermissionExecutor} lambda instead of Mockito mocks
 * because the gRPC blocking stub is a {@code final} class that Mockito's ByteBuddy
 * instrumentation cannot handle on JDK 25. This approach is consistent with the project's
 * existing test strategy (see {@code FeatureSpiceDBQueryTest}).
 */
class FgaSpiceDBQueryTest {

    // -------------------------------------------------------------------------
    // Permissionship outcome mapping
    // -------------------------------------------------------------------------

    @Test
    void query_permissionGranted_returnsAllowed() {
        FgaSpiceDBQuery query = queryWith(
                CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);

        EntitlementsResult result = query.query(
                new EntitySubjectContext("service_account", "svc-deployer-01"),
                new EntityRequestContext("document", "doc-789", "viewer"));

        assertTrue(result.result(), "PERMISSIONSHIP_HAS_PERMISSION → result must be true");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_permissionDenied_returnsDenied() {
        FgaSpiceDBQuery query = queryWith(
                CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);

        EntitlementsResult result = query.query(
                new EntitySubjectContext("service_account", "svc-deployer-01"),
                new EntityRequestContext("document", "doc-789", "viewer"));

        assertFalse(result.result(), "PERMISSIONSHIP_NO_PERMISSION → result must be false");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_conditionalPermission_returnsAllowed() {
        FgaSpiceDBQuery query = queryWith(
                CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION);

        EntitlementsResult result = query.query(
                new EntitySubjectContext("service_account", "svc-deployer-01"),
                new EntityRequestContext("document", "doc-789", "viewer"));

        assertTrue(result.result(), "PERMISSIONSHIP_CONDITIONAL_PERMISSION → result must be true");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_unspecifiedPermissionship_returnsDenied() {
        FgaSpiceDBQuery query = queryWith(
                CheckPermissionResponse.Permissionship.PERMISSIONSHIP_UNSPECIFIED);

        EntitlementsResult result = query.query(
                new EntitySubjectContext("device", "dev-42"),
                new EntityRequestContext("project", "proj-1", "editor"));

        assertFalse(result.result(), "PERMISSIONSHIP_UNSPECIFIED → result must be false");
    }

    // -------------------------------------------------------------------------
    // Request construction — subject type/id, resource type/id, relation
    // -------------------------------------------------------------------------

    @Test
    void query_requestConstruction_correctSubjectTypeAndId() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        });

        query.query(
                new EntitySubjectContext("service_account", "svc-deployer-01"),
                new EntityRequestContext("document", "doc-789", "viewer"));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request, "request must have been captured");

        assertEquals("service_account",
                request.getSubject().getObject().getObjectType(),
                "subject object type must match entityType");
        assertEquals(base64("svc-deployer-01"),
                request.getSubject().getObject().getObjectId(),
                "subject object id must be base64-encoded entityId");
    }

    @Test
    void query_requestConstruction_correctResourceTypeAndId() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        });

        query.query(
                new EntitySubjectContext("service_account", "svc-deployer-01"),
                new EntityRequestContext("document", "doc-789", "viewer"));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request);

        assertEquals("document",
                request.getResource().getObjectType(),
                "resource object type must match resourceType");
        assertEquals(base64("doc-789"),
                request.getResource().getObjectId(),
                "resource object id must be base64-encoded resourceId");
    }

    @Test
    void query_requestConstruction_correctRelation() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        });

        query.query(
                new EntitySubjectContext("service_account", "svc-deployer-01"),
                new EntityRequestContext("document", "doc-789", "viewer"));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request);

        assertEquals("viewer", request.getPermission(),
                "permission field must match the relation from EntityRequestContext");
    }

    // -------------------------------------------------------------------------
    // Base64 encoding verification
    // -------------------------------------------------------------------------

    @Test
    void query_base64Encoding_subjectIdIsUrlSafeNoPadding() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        });

        // Use a value that would produce padding characters in standard Base64
        query.query(
                new EntitySubjectContext("device", "dev-id-1"),
                new EntityRequestContext("project", "proj-abc", "owner"));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request);

        String encodedSubject = request.getSubject().getObject().getObjectId();
        String encodedResource = request.getResource().getObjectId();

        assertEquals(base64("dev-id-1"), encodedSubject,
                "subject id must use URL-safe Base64 without padding");
        assertEquals(base64("proj-abc"), encodedResource,
                "resource id must use URL-safe Base64 without padding");

        // Verify no standard padding character '=' appears
        assertFalse(encodedSubject.contains("="),
                "URL-safe no-padding Base64 must not contain '='");
        assertFalse(encodedResource.contains("="),
                "URL-safe no-padding Base64 must not contain '='");
    }

    @Test
    void query_base64Encoding_unicodeEntityId_encodedCorrectly() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        });

        query.query(
                new EntitySubjectContext("user", "user@example.com"),
                new EntityRequestContext("repo", "my-repo/project", "read"));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request);

        assertEquals(base64("user@example.com"),
                request.getSubject().getObject().getObjectId(),
                "unicode / special-char entity id must be UTF-8 encoded then base64'd");
        assertEquals(base64("my-repo/project"),
                request.getResource().getObjectId(),
                "resource id with slash must be UTF-8 encoded then base64'd");
    }

    // -------------------------------------------------------------------------
    // Caveat context — time-based access checks
    // -------------------------------------------------------------------------

    @Test
    void query_withAtTimestamp_requestIncludesCaveatContext() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);
        });

        java.time.Instant at = java.time.Instant.parse("2026-02-01T00:00:00Z");
        query.query(
                new EntitySubjectContext("user", "Tim"),
                new EntityRequestContext("document", "doc-1", "read_doc", at));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request);
        assertTrue(request.hasContext(), "request must include caveat context when at is provided");
        assertEquals(at.toString(),
                request.getContext().getFieldsOrThrow("at").getStringValue(),
                "caveat context 'at' field must contain ISO-8601 timestamp");
    }

    @Test
    void query_withNullAt_requestHasNoCaveatContext() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        });

        query.query(
                new EntitySubjectContext("user", "Alice"),
                new EntityRequestContext("document", "doc-1", "read_doc"));

        CheckPermissionRequest request = captured.get();
        assertNotNull(request);
        assertFalse(request.hasContext(),
                "request must not include caveat context when at is null");
    }

    @Test
    void query_withAtTimestamp_caveatContextUsesIso8601Format() {
        AtomicReference<CheckPermissionRequest> captured = new AtomicReference<>();

        FgaSpiceDBQuery query = new FgaSpiceDBQuery(req -> {
            captured.set(req);
            return permissionResponse(
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);
        });

        java.time.Instant at = java.time.Instant.parse("2026-03-15T14:30:00Z");
        query.query(
                new EntitySubjectContext("service_account", "svc-01"),
                new EntityRequestContext("project", "proj-1", "editor", at));

        CheckPermissionRequest request = captured.get();
        String atValue = request.getContext().getFieldsOrThrow("at").getStringValue();
        // Instant.toString() produces ISO-8601: "2026-03-15T14:30:00Z"
        assertEquals("2026-03-15T14:30:00Z", atValue,
                "caveat context at value must be ISO-8601 format");
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link FgaSpiceDBQuery} backed by a canned executor that returns a response
     * with the given permissionship.
     */
    private static FgaSpiceDBQuery queryWith(
            CheckPermissionResponse.Permissionship permissionship) {
        CheckPermissionResponse response = permissionResponse(permissionship);
        return new FgaSpiceDBQuery(req -> response);
    }

    private static CheckPermissionResponse permissionResponse(
            CheckPermissionResponse.Permissionship permissionship) {
        return CheckPermissionResponse.newBuilder()
                .setPermissionship(permissionship)
                .build();
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
