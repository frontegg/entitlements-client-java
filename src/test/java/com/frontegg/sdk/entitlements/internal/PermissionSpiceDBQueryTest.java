package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckBulkPermissionsResponseItem;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
 * Unit tests for {@link PermissionSpiceDBQuery}.
 *
 * <p>Uses a hand-crafted {@link BulkPermissionsExecutor} lambda instead of Mockito mocks
 * because the gRPC blocking stub is a {@code final} class that Mockito's ByteBuddy
 * instrumentation cannot handle on JDK 25. This approach is consistent with the project's
 * existing test strategy (see {@code FeatureSpiceDBQueryTest}).
 */
class PermissionSpiceDBQueryTest {

    // -------------------------------------------------------------------------
    // Single permission — permissionship outcome mapping
    // -------------------------------------------------------------------------

    @Test
    void query_singlePermission_userEntitled_returnsAllowed() {
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "user entitled → result must be true");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_singlePermission_tenantEntitledUserDenied_returnsAllowed() {
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "tenant entitled → result must be true even if user denied");
    }

    @Test
    void query_singlePermission_bothDenied_returnsDenied() {
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "both denied → result must be false");
    }

    // -------------------------------------------------------------------------
    // Multiple permissions — AND logic
    // -------------------------------------------------------------------------

    @Test
    void query_multiplePermissions_allEntitled_returnsAllowed() {
        // Both permissions: user is entitled (tenant denied)
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext(List.of("reports:read", "reports:export")));

        assertTrue(result.result(), "all permissions entitled → result must be true");
    }

    @Test
    void query_multiplePermissions_oneKeyDenied_returnsDenied() {
        // First permission: both user and tenant denied
        // Second permission: user is entitled
        // Expected: denied (AND logic — all must be entitled)
        PermissionSpiceDBQuery denyFirstQuery = new PermissionSpiceDBQuery(req -> {
            CheckBulkPermissionsResponse.Builder responseBuilder = CheckBulkPermissionsResponse.newBuilder();
            List<CheckBulkPermissionsRequestItem> items = req.getItemsList();
            // Items are: [user→perm1, tenant→perm1, user→perm2, tenant→perm2]
            // Deny both items for perm1 (indices 0,1), allow user item for perm2 (index 2)
            for (int i = 0; i < items.size(); i++) {
                CheckPermissionResponse.Permissionship p = (i < 2)
                        ? CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION
                        : CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION;
                responseBuilder.addPairs(pairWithRequest(items.get(i), p));
            }
            return responseBuilder.build();
        });

        EntitlementsResult result = denyFirstQuery.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext(List.of("reports:read", "reports:export")));

        assertFalse(result.result(), "one key denied → result must be false (AND logic)");
    }

    // -------------------------------------------------------------------------
    // Request construction — 2 items per permission key
    // -------------------------------------------------------------------------

    @Test
    void query_twoPermissionKeys_requestHasFourItems() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new PermissionRequestContext(List.of("perm:one", "perm:two")));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request, "request must have been captured");
        assertEquals(4, request.getItemsCount(),
                "2 permission keys × 2 subject types = 4 items");
    }

    @Test
    void query_requestConstruction_correctResourceTypeAndRelation() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new PermissionRequestContext("my-permission"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(2, request.getItemsCount(), "must send exactly 2 items for a single key");

        CheckBulkPermissionsRequestItem userItem = request.getItems(0);
        assertEquals("frontegg_user", userItem.getSubject().getObject().getObjectType());
        assertEquals(base64("user-abc"), userItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_permission", userItem.getResource().getObjectType(),
                "resource type must be frontegg_permission, not frontegg_feature");
        assertEquals(base64("my-permission"), userItem.getResource().getObjectId());
        assertEquals("entitled", userItem.getPermission());

        CheckBulkPermissionsRequestItem tenantItem = request.getItems(1);
        assertEquals("frontegg_tenant", tenantItem.getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), tenantItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_permission", tenantItem.getResource().getObjectType());
        assertEquals(base64("my-permission"), tenantItem.getResource().getObjectId());
        assertEquals("entitled", tenantItem.getPermission());
    }

    // -------------------------------------------------------------------------
    // Caveat context
    // -------------------------------------------------------------------------

    @Test
    void query_withAttributes_caveatContextAttached() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1",
                        Map.of("plan", "enterprise", "active_at", "2026-01-01")),
                new PermissionRequestContext("reports:read"));

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

        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1", Map.of()),
                new PermissionRequestContext("reports:read"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertFalse(item.hasContext(),
                    "caveat context must NOT be present when attributes are empty");
        }
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    /**
     * Builds a response that mirrors the items from the request, assigning permissionships
     * in round-robin order (userPermissionship for even indices, tenantPermissionship for odd).
     * This simulates the SpiceDB response pattern: [user→key, tenant→key, user→key2, ...].
     */
    private static CheckBulkPermissionsResponse responseForRequest(
            CheckBulkPermissionsRequest req,
            CheckPermissionResponse.Permissionship userPermissionship,
            CheckPermissionResponse.Permissionship tenantPermissionship) {
        CheckBulkPermissionsResponse.Builder responseBuilder = CheckBulkPermissionsResponse.newBuilder();
        List<CheckBulkPermissionsRequestItem> items = req.getItemsList();
        for (int i = 0; i < items.size(); i++) {
            // Even index = user item, odd index = tenant item (pairs are added user first)
            CheckPermissionResponse.Permissionship p = (i % 2 == 0) ? userPermissionship : tenantPermissionship;
            responseBuilder.addPairs(pairWithRequest(items.get(i), p));
        }
        return responseBuilder.build();
    }

    private static CheckBulkPermissionsPair pairWithRequest(
            CheckBulkPermissionsRequestItem requestItem,
            CheckPermissionResponse.Permissionship permissionship) {
        return CheckBulkPermissionsPair.newBuilder()
                .setRequest(requestItem)
                .setItem(CheckBulkPermissionsResponseItem.newBuilder()
                        .setPermissionship(permissionship)
                        .build())
                .build();
    }

    private static CheckBulkPermissionsResponse emptyResponse() {
        return CheckBulkPermissionsResponse.newBuilder().build();
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
