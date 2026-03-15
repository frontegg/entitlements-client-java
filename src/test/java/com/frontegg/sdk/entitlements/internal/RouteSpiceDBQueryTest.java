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
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
 * Unit tests for {@link RouteSpiceDBQuery}.
 *
 * <p>Uses a hand-crafted {@link BulkPermissionsExecutor} lambda instead of Mockito mocks
 * because the gRPC blocking stub is a {@code final} class that Mockito's ByteBuddy
 * instrumentation cannot handle on JDK 25. This approach is consistent with the project's
 * existing test strategy (see {@code FeatureSpiceDBQueryTest}).
 */
class RouteSpiceDBQueryTest {

    // -------------------------------------------------------------------------
    // Permissionship outcome mapping
    // -------------------------------------------------------------------------

    @Test
    void query_userEntitled_returnsAllowed() {
        RouteSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "user entitled → result must be true");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_tenantEntitledUserDenied_returnsAllowed() {
        RouteSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("POST", "/api/v1/data"));

        assertTrue(result.result(), "tenant entitled → result must be true even if user denied");
    }

    @Test
    void query_userConditionalPermission_returnsAllowed() {
        RouteSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "user conditional permission → result must be true");
    }

    @Test
    void query_tenantConditionalPermission_returnsAllowed() {
        RouteSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("POST", "/api/v1/data"));

        assertTrue(result.result(), "tenant conditional permission → result must be true");
    }

    @Test
    void query_bothDenied_returnsDenied() {
        RouteSpiceDBQuery query = queryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("DELETE", "/api/v1/items/42"));

        assertFalse(result.result(), "both denied → result must be false");
    }

    // -------------------------------------------------------------------------
    // Route key format
    // -------------------------------------------------------------------------

    @Test
    void query_routeKeyFormat_isMethodColonPath() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new RouteRequestContext("get", "/api/v1/reports"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);

        // Route key should be "GET:/api/v1/reports" (method uppercased)
        String expectedRouteKey = "GET:/api/v1/reports";
        String expectedB64 = base64(expectedRouteKey);

        // Both items reference the same route resource
        for (int i = 0; i < request.getItemsCount(); i++) {
            assertEquals(expectedB64, request.getItems(i).getResource().getObjectId(),
                    "resource object ID must be base64(METHOD:PATH) with uppercased method");
        }
    }

    // -------------------------------------------------------------------------
    // Request construction — subject types, object types, relation, base64 IDs
    // -------------------------------------------------------------------------

    @Test
    void query_requestConstruction_correctSubjectTypesObjectTypesAndRelation() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request, "request must have been captured");
        assertEquals(2, request.getItemsCount(), "must send exactly 2 items");

        String expectedRouteB64 = base64("GET:/api/v1/reports");

        CheckBulkPermissionsRequestItem userItem = request.getItems(0);
        assertEquals("frontegg_user", userItem.getSubject().getObject().getObjectType());
        assertEquals(base64("user-abc"), userItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_route", userItem.getResource().getObjectType());
        assertEquals(expectedRouteB64, userItem.getResource().getObjectId());
        assertEquals("entitled", userItem.getPermission());

        CheckBulkPermissionsRequestItem tenantItem = request.getItems(1);
        assertEquals("frontegg_tenant", tenantItem.getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), tenantItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_route", tenantItem.getResource().getObjectType());
        assertEquals(expectedRouteB64, tenantItem.getResource().getObjectId());
        assertEquals("entitled", tenantItem.getPermission());
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void query_errorInPair_throwsEntitlementsQueryException() {
        CheckBulkPermissionsResponse errorResponse = CheckBulkPermissionsResponse.newBuilder()
                .addPairs(CheckBulkPermissionsPair.newBuilder()
                        .setError(Status.newBuilder().setMessage("permission check failed").build())
                        .build())
                .build();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> errorResponse);

        assertThrows(EntitlementsQueryException.class,
                () -> query.query(
                        new UserSubjectContext("user-1", "tenant-1"),
                        new RouteRequestContext("GET", "/api/v1/reports")),
                "error pair must throw EntitlementsQueryException");
    }

    // -------------------------------------------------------------------------
    // Caveat context
    // -------------------------------------------------------------------------

    @Test
    void query_withAttributes_caveatContextAttached() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1",
                        Map.of("plan", "enterprise", "active_at", "2026-01-01")),
                new RouteRequestContext("GET", "/api/v1/reports"));

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

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1", Map.of()),
                new RouteRequestContext("POST", "/api/v1/data"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertFalse(item.hasContext(),
                    "caveat context must NOT be present when attributes are empty");
        }
    }

    // -------------------------------------------------------------------------
    // HTTP method case normalization
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "get, GET",
            "GET, GET",
            "Get, GET",
            "gET, GET",
            "post, POST",
            "patch, PATCH"
    })
    void query_httpMethodCase_alwaysUppercased(String input, String expected) {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext(input, "/api/v1/resources"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);

        // Route key should have the expected uppercased method
        String expectedRouteKey = expected + ":/api/v1/resources";
        String expectedB64 = base64(expectedRouteKey);

        for (int i = 0; i < request.getItemsCount(); i++) {
            assertEquals(expectedB64, request.getItems(i).getResource().getObjectId(),
                    "resource object ID must be base64(UPPERCASED_METHOD:PATH)");
        }
    }

    // -------------------------------------------------------------------------
    // Paths with special characters
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/items?id=123",
            "/api/v1/items%2F123",
            "/api/v1/items/테스트",
            "/api/v1/reports#section",
            "/api/v1/path with spaces"
    })
    void query_pathWithSpecialCharacters_encodedCorrectly(String path) {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", path));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request, "request must be captured");

        // Should not throw and request should be properly constructed
        assertEquals(2, request.getItemsCount(), "must send exactly 2 items");

        // Verify resource is correctly encoded
        String expectedRouteKey = "GET:" + path;
        String expectedB64 = base64(expectedRouteKey);

        for (int i = 0; i < request.getItemsCount(); i++) {
            assertEquals(expectedB64, request.getItems(i).getResource().getObjectId(),
                    "resource object ID must be base64(GET:PATH) with special chars preserved");
        }
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link RouteSpiceDBQuery} backed by a canned executor that returns a response
     * with the given response items mapped to pairs (one per item).
     */
    private static RouteSpiceDBQuery queryWith(CheckBulkPermissionsResponseItem... items) {
        CheckBulkPermissionsResponse response = responseWith(items);
        return new RouteSpiceDBQuery(req -> response);
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
