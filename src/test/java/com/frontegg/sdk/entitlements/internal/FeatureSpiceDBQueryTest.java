package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckBulkPermissionsResponseItem;
import com.authzed.api.v1.CheckPermissionResponse;
import com.google.rpc.Status;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // tenant item is always first (index 0); user item is second (index 1) when userId present
        CheckBulkPermissionsRequestItem tenantItem = request.getItems(0);
        assertEquals("frontegg_tenant", tenantItem.getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), tenantItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_feature", tenantItem.getResource().getObjectType());
        assertEquals(base64("my-feature"), tenantItem.getResource().getObjectId());
        assertEquals("access", tenantItem.getPermission());

        CheckBulkPermissionsRequestItem userItem = request.getItems(1);
        assertEquals("frontegg_user", userItem.getSubject().getObject().getObjectType());
        assertEquals(base64("user-abc"), userItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_feature", userItem.getResource().getObjectType());
        assertEquals(base64("my-feature"), userItem.getResource().getObjectId());
        assertEquals("access", userItem.getPermission());
    }

    @Test
    void query_nullUserId_onlyTenantItemSent() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext(null, "tenant-xyz"),
                new FeatureRequestContext("my-feature"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(1, request.getItemsCount(), "only tenant item must be sent when userId is null");
        assertEquals("frontegg_tenant", request.getItems(0).getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), request.getItems(0).getSubject().getObject().getObjectId());
    }

    @Test
    void query_blankUserId_onlyTenantItemSent() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("   ", "tenant-xyz"),
                new FeatureRequestContext("my-feature"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(1, request.getItemsCount(), "only tenant item must be sent when userId is blank");
        assertEquals("frontegg_tenant", request.getItems(0).getSubject().getObject().getObjectType());
    }

    @Test
    void query_nullUserId_tenantEntitled_returnsAllowed() {
        // When userId is null only the tenant item is sent; tenant entitled → result must be true.
        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext(null, "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertTrue(result.result(), "tenant entitled with null userId → result must be true");
    }

    @Test
    void query_nullUserId_tenantDenied_returnsDenied() {
        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext(null, "tenant-1"),
                new FeatureRequestContext("feature-key"));

        assertFalse(result.result(), "tenant denied with null userId → result must be false");
    }

    // -------------------------------------------------------------------------
    // Caveat context
    // -------------------------------------------------------------------------

    @Test
    void query_withAttributes_caveatContextContainsUserContextWrapper() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        }, TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-1", "tenant-1",
                        Map.of("plan", "enterprise")),
                new FeatureRequestContext("feature-key"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext(), "caveat context must always be present");
            assertTrue(item.getContext().containsFields("user_context"),
                    "context must be wrapped under 'user_context'");
            com.google.protobuf.Struct uc = item.getContext().getFieldsOrThrow("user_context").getStructValue();
            assertEquals("enterprise", uc.getFieldsOrThrow("plan").getStringValue());
            assertTrue(uc.containsFields("now"), "user_context must contain 'now'");
        }
    }

    @Test
    void query_withoutAttributes_caveatContextStillPresentWithNow() {
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
            assertTrue(item.hasContext(), "caveat context must always be present");
            assertTrue(item.getContext().containsFields("user_context"),
                    "context must be wrapped under 'user_context'");
            com.google.protobuf.Struct uc = item.getContext().getFieldsOrThrow("user_context").getStructValue();
            assertTrue(uc.containsFields("now"), "user_context must contain 'now'");
        }
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void query_spiceDBReturnsErrorInPair_throwsEntitlementsQueryException() {
        // Build a CheckBulkPermissionsResponse with an error pair
        CheckBulkPermissionsResponse errorResponse = CheckBulkPermissionsResponse.newBuilder()
                .addPairs(CheckBulkPermissionsPair.newBuilder()
                        .setError(Status.newBuilder()
                                .setCode(13)
                                .setMessage("internal error")
                                .build())
                        .build())
                .build();

        FeatureSpiceDBQuery query = new FeatureSpiceDBQuery(req -> errorResponse, TEST_CONSISTENCY);

        assertThrows(EntitlementsQueryException.class,
                () -> query.query(
                        new UserSubjectContext("user-1", "tenant-1"),
                        new FeatureRequestContext("feature-key")),
                "error pair in response must throw EntitlementsQueryException");
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
