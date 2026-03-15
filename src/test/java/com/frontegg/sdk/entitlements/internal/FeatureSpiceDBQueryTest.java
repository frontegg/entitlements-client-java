package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckBulkPermissionsResponseItem;
import com.authzed.api.v1.CheckPermissionResponse;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FeatureSpiceDBQuery}.
 *
 * <p>Uses a hand-crafted {@link BulkPermissionsExecutor} lambda instead of Mockito mocks
 * because the gRPC blocking stub is a {@code final} class that Mockito's ByteBuddy
 * instrumentation cannot handle on JDK 25. This approach is consistent with the project's
 * existing test strategy (see {@code SpiceDBEntitlementsClientTest}).
 */
class FeatureSpiceDBQueryTest {

    private static final java.util.function.Supplier<com.authzed.api.v1.Consistency> TEST_CONSISTENCY =
            () -> com.authzed.api.v1.Consistency.newBuilder().setMinimizeLatency(true).build();

    // -------------------------------------------------------------------------
    // Permissionship outcome mapping
    // -------------------------------------------------------------------------

    @Test
    void query_userEntitled_returnsAllowed() {
        FeatureSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertTrue(result.result(), "user entitled → result must be true");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_tenantEntitledUserDenied_returnsAllowed() {
        FeatureSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertTrue(result.result(), "tenant entitled → result must be true even if user denied");
    }

    @Test
    void query_userConditionalPermission_returnsDenied() {
        FeatureSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_tenantConditionalPermission_returnsDenied() {
        FeatureSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_bothDenied_returnsDenied() {
        FeatureSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertFalse(result.result(), "both denied → result must be false");
    }

    // -------------------------------------------------------------------------
    // Request construction — subject types, object types, relation, base64 IDs
    // -------------------------------------------------------------------------

    @Test
    void query_requestConstruction_correctSubjectTypesObjectTypesAndRelation() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new FeatureRequestContext("my-feature"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request, "request must have been captured");
        assertEquals(2, request.getItemsCount(), "must send exactly 2 items");

        CheckBulkPermissionsRequestItem userItem = request.getItems(0);
        assertEquals("frontegg_user", userItem.getSubject().getObject().getObjectType());
        assertEquals(base64("user-abc"), userItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_feature", userItem.getResource().getObjectType());
        assertEquals(base64("my-feature"), userItem.getResource().getObjectId());
        assertEquals("entitled", userItem.getPermission());

        CheckBulkPermissionsRequestItem tenantItem = request.getItems(1);
        assertEquals("frontegg_tenant", tenantItem.getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), tenantItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_feature", tenantItem.getResource().getObjectType());
        assertEquals(base64("my-feature"), tenantItem.getResource().getObjectId());
        assertEquals("entitled", tenantItem.getPermission());
    }

    // -------------------------------------------------------------------------
    // Caveat context
    // -------------------------------------------------------------------------

    @Test
    void query_withAttributes_caveatContextAttached() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-1", "tenant-1",
                        Map.of("plan", "enterprise", "active_at", "2026-01-01")),
                new FeatureRequestContext("feature-key"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext(),
                    "caveat context must be present when attributes are non-empty");
            assertNotNull(item.getContext());
            assertTrue(item.getContext().getFieldsCount() > 0,
                    "context struct must contain the user attributes");
        }
    }

    @Test
    void query_withoutAttributes_caveatContextNotAttached() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-1", "tenant-1", Map.of()),
                new FeatureRequestContext("feature-key"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertFalse(item.hasContext(),
                    "caveat context must NOT be present when attributes are empty");
        }
    }

    // -------------------------------------------------------------------------
    // Time-based access — at parameter
    // -------------------------------------------------------------------------

    @Test
    void query_withAt_caveatContextContainsAtField() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key", at));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext(),
                    "caveat context must be present when at is non-null");
            assertTrue(item.getContext().containsFields("at"),
                    "caveat context must contain 'at' field");
            assertEquals("2026-01-01T00:00:00Z",
                    item.getContext().getFieldsOrThrow("at").getStringValue(),
                    "'at' field must be ISO-8601 string");
        }
    }

    @Test
    void query_withAtAndAttributes_caveatContextContainsBoth() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();
        Instant at = Instant.parse("2025-06-15T12:00:00Z");

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-1", "tenant-1", Map.of("plan", "enterprise")),
                new FeatureRequestContext("feature-key", at));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext());
            assertEquals("enterprise",
                    item.getContext().getFieldsOrThrow("plan").getStringValue());
            assertEquals("2025-06-15T12:00:00Z",
                    item.getContext().getFieldsOrThrow("at").getStringValue());
        }
    }

    @Test
    void query_withNullAt_caveatContextNotAttachedWhenNoAttributes() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        // Explicitly pass null at — same as convenience constructor
        query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("feature-key", null));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertFalse(item.hasContext(),
                    "caveat context must NOT be present when both attributes and at are absent");
        }
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link FeatureSpiceDBQuery} backed by a canned executor that returns a response
     * with the given response items mapped to pairs (one per item).
     */
    private static FeatureSpiceDBQuery queryWith(CheckBulkPermissionsResponseItem... items) {
        CheckBulkPermissionsResponse response = responseWith(items);
        return new FeatureSpiceDBQuery(req -> response, TEST_CONSISTENCY);
    }

    private static CheckBulkPermissionsResponse responseWith(
            CheckBulkPermissionsResponseItem... items) {
        CheckBulkPermissionsResponse.Builder builder = CheckBulkPermissionsResponse.newBuilder();
        for (CheckBulkPermissionsResponseItem item : items) {
            builder.addPairs(CheckBulkPermissionsPair.newBuilder().setItem(item).build());
        }
        return builder.build();
    }

    private static CheckBulkPermissionsResponse emptyResponse() {
        return CheckBulkPermissionsResponse.newBuilder().build();
    }

    private static CheckBulkPermissionsResponseItem permissionship(
            CheckPermissionResponse.Permissionship permissionship) {
        return CheckBulkPermissionsResponseItem.newBuilder()
                .setPermissionship(permissionship)
                .build();
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
