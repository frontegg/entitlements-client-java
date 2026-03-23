package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckBulkPermissionsResponseItem;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.ContextualizedCaveat;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.ReadRelationshipsResponse;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.SubjectReference;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RouteSpiceDBQuery}.
 *
 * <p>Uses hand-crafted lambda test doubles instead of Mockito mocks because the gRPC blocking
 * stub is a {@code final} class that Mockito's ByteBuddy instrumentation cannot handle on
 * JDK 25. This approach is consistent with the project's existing test strategy.
 */
class RouteSpiceDBQueryTest {

    private static final java.util.function.Supplier<com.authzed.api.v1.Consistency> TEST_CONSISTENCY =
            () -> com.authzed.api.v1.Consistency.newBuilder().setMinimizeLatency(true).build();

    // -------------------------------------------------------------------------
    // Policy engine — allow/deny/noMatch
    // -------------------------------------------------------------------------

    @Test
    void query_allowPolicy_returnsAllowed() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "GET /api/.*", "allow", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "allow policy must return true");
        assertFalse(result.monitoring(), "monitoring must be false when not set");
    }

    @Test
    void query_denyPolicy_returnsDenied() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "GET /api/.*", "deny", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "deny policy must return false");
        assertFalse(result.monitoring());
    }

    @Test
    void query_noMatchingRoute_returnsDenied() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "POST /other/.*", "allow", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "no matching route must return denied");
        assertFalse(result.monitoring(), "monitoring must be false when no match");
    }

    @Test
    void query_monitoring_flagSetInResult() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "GET /api/.*", "allow", 0.0, true));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result());
        assertTrue(result.monitoring(), "monitoring flag must be propagated from caveat context");
    }

    @Test
    void query_ruleBasedMonitoringFlag_propagatedToResult() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, true));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "ruleBased rule must evaluate to true (user entitled)");
        assertTrue(result.monitoring(), "monitoring flag from ruleBased rule must be propagated to result");
    }

    @Test
    void query_priorityOrdering_highestPriorityWins() {
        // deny has priority 10, allow has priority 5 — deny wins
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id-allow", "GET /api/.*", "allow", 5.0, false),
                routeRelationship("entitled", "id-deny",  "GET /api/.*", "deny",  10.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "highest priority (deny=10) must win over lower priority allow=5");
    }

    // -------------------------------------------------------------------------
    // ruleBased policy
    // -------------------------------------------------------------------------

    @Test
    void query_ruleBasedWithRequiredPermission_userHasPermission_proceedsToSpiceDB() {
        String permissionKey = "fe.billing.read";
        String hashedPerm = base64(permissionKey);

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("required_permission", hashedPerm, "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(permissionKey), Map.of()),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "user has permission → SpiceDB bulk check → allowed");
    }

    @Test
    void query_ruleBasedWithRequiredPermission_userLacksPermission_returnsDenied() {
        String permissionKey = "fe.billing.read";
        String hashedPerm = base64(permissionKey);

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("required_permission", hashedPerm, "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        // user has a different permission, not "fe.billing.read"
        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of("fe.users.read"), Map.of()),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "user lacks required permission → denied without SpiceDB call");
    }

    @Test
    void query_ruleBasedNoRequiredPermissions_proceedsToSpiceDB() {
        // ruleBased rule with relation "entitled" (not "required_permission") — no permission check
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "ruleBased with no required_permission goes straight to SpiceDB bulk check");
    }

    @Test
    void query_invalidRegexPatternInRelationship_skippedAndDoesNotCrash() {
        // Build a route relationship with an invalid regex pattern: "[unclosed"
        Struct.Builder ctx = Struct.newBuilder()
                .putFields("pattern",    Value.newBuilder().setStringValue("[unclosed").build())
                .putFields("policy_type", Value.newBuilder().setStringValue("ruleBased").build())
                .putFields("priority",   Value.newBuilder().setNumberValue(0.0).build())
                .putFields("monitoring", Value.newBuilder().setBoolValue(false).build());

        ContextualizedCaveat caveat = ContextualizedCaveat.newBuilder()
                .setCaveatName("route_caveat")
                .setContext(ctx.build())
                .build();

        Relationship rel = Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                        .setObjectType("frontegg_route")
                        .setObjectId("route-resource-id")
                        .build())
                .setRelation("entitled")
                .setSubject(SubjectReference.newBuilder()
                        .setObject(ObjectReference.newBuilder()
                                .setObjectType("frontegg_user")
                                .setObjectId("some-id")
                                .build())
                        .build())
                .setOptionalCaveat(caveat)
                .build();

        ReadRelationshipsResponse response = ReadRelationshipsResponse.newBuilder()
                .setRelationship(rel)
                .build();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> List.of(response),
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        // Should not throw an exception; the invalid pattern is skipped and the route does not match
        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "no valid rules after skipping invalid pattern must return denied");
    }

    // -------------------------------------------------------------------------
    // Cache
    // -------------------------------------------------------------------------

    @Test
    void query_cacheUsed_readRelationshipsCalledOnce() {
        AtomicInteger readCallCount = new AtomicInteger(0);

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "GET /api/.*", "allow", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> {
                    readCallCount.incrementAndGet();
                    return routes;
                },
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        query.query(new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));
        query.query(new UserSubjectContext("user-2", "tenant-2"),
                new RouteRequestContext("GET", "/api/v1/other"));

        assertEquals(1, readCallCount.get(),
                "ReadRelationships must be called only once within the cache TTL");
    }

    @Test
    void query_cacheExpiry_readRelationshipsCalledAgainAfterTTL() throws InterruptedException {
        AtomicInteger readCallCount = new AtomicInteger(0);

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "some-id", "GET /api/.*", "allow", 0.0, false));

        // Use custom TTL of 50ms for faster test execution
        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> {
                    readCallCount.incrementAndGet();
                    return routes;
                },
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY,
                50L);  // 50ms TTL

        query.query(new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertEquals(1, readCallCount.get(), "first call must fetch from SpiceDB");

        // Sleep 100ms to exceed the 50ms TTL
        Thread.sleep(100);

        query.query(new UserSubjectContext("user-2", "tenant-2"),
                new RouteRequestContext("GET", "/api/v1/other"));

        assertEquals(2, readCallCount.get(),
                "ReadRelationships must be called again after cache TTL expires");
    }

    // -------------------------------------------------------------------------
    // Permissionship outcome mapping (ruleBased → CheckBulkPermissions)
    // -------------------------------------------------------------------------

    @Test
    void query_userEntitled_returnsAllowed() {
        RouteSpiceDBQuery query = ruleBasedQueryWith(
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
        RouteSpiceDBQuery query = ruleBasedQueryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("POST", "/api/v1/data"));

        assertTrue(result.result(), "tenant entitled → result must be true even if user denied");
    }

    @Test
    void query_userConditionalPermission_returnsDenied() {
        RouteSpiceDBQuery query = ruleBasedQueryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_tenantConditionalPermission_returnsDenied() {
        RouteSpiceDBQuery query = ruleBasedQueryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("POST", "/api/v1/data"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_ruleBasedCheckBulkPermissionsReturnsConditional_returnsDeniedFailClosed() {
        // Build a CheckBulkPermissionsResponse with a pair where the item's permissionship is CONDITIONAL_PERMISSION
        CheckBulkPermissionsResponse response = CheckBulkPermissionsResponse.newBuilder()
                .addPairs(CheckBulkPermissionsPair.newBuilder()
                        .setItem(CheckBulkPermissionsResponseItem.newBuilder()
                                .setPermissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION)
                                .build())
                        .build())
                .build();

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> response,
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(),
                "ruleBased with CONDITIONAL_PERMISSION in CheckBulkPermissions must return denied (fail-closed)");
    }

    @Test
    void query_bothDenied_returnsDenied() {
        RouteSpiceDBQuery query = ruleBasedQueryWith(
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("DELETE", "/api/v1/items/42"));

        assertFalse(result.result(), "both denied → result must be false");
    }

    // -------------------------------------------------------------------------
    // Route key format (CheckBulkPermissions path)
    // -------------------------------------------------------------------------

    @Test
    void query_routeKeyFormat_isMethodColonPath() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    captured.set(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new RouteRequestContext("get", "/api/v1/reports"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);

        // Route key should be "GET:/api/v1/reports" (method uppercased)
        String expectedRouteKey = "GET:/api/v1/reports";
        String expectedB64 = base64(expectedRouteKey);

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

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    captured.set(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request, "request must have been captured");
        assertEquals(2, request.getItemsCount(), "must send exactly 2 items");

        String expectedRouteB64 = base64("GET:/api/v1/reports");

        // tenant item is always first (index 0); user item is second (index 1) when userId present
        CheckBulkPermissionsRequestItem tenantItem = request.getItems(0);
        assertEquals("frontegg_tenant", tenantItem.getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), tenantItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_route", tenantItem.getResource().getObjectType());
        assertEquals(expectedRouteB64, tenantItem.getResource().getObjectId());
        assertEquals("access", tenantItem.getPermission());

        CheckBulkPermissionsRequestItem userItem = request.getItems(1);
        assertEquals("frontegg_user", userItem.getSubject().getObject().getObjectType());
        assertEquals(base64("user-abc"), userItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_route", userItem.getResource().getObjectType());
        assertEquals(expectedRouteB64, userItem.getResource().getObjectId());
        assertEquals("access", userItem.getPermission());
    }

    @Test
    void query_nullUserId_onlyTenantItemSent() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    captured.set(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext(null, "tenant-xyz"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(1, request.getItemsCount(), "only tenant item must be sent when userId is null");
        assertEquals("frontegg_tenant", request.getItems(0).getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), request.getItems(0).getSubject().getObject().getObjectId());
    }

    @Test
    void query_blankUserId_onlyTenantItemSent() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    captured.set(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

        query.query(
                new UserSubjectContext("   ", "tenant-xyz"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(1, request.getItemsCount(), "only tenant item must be sent when userId is blank");
        assertEquals("frontegg_tenant", request.getItems(0).getSubject().getObject().getObjectType());
    }

    @Test
    void query_nullUserId_tenantEntitled_returnsAllowed() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext(null, "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(), "tenant entitled with null userId → result must be true");
    }

    @Test
    void query_nullUserId_tenantDenied_returnsDenied() {
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> responseWith(
                        permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION)),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext(null, "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertFalse(result.result(), "tenant denied with null userId → result must be false");
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

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> errorResponse,
                TEST_CONSISTENCY);

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

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    requests.add(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

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

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "POST /api/.*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    requests.add(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

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

        // Pattern matches "METHOD /api/v1/resources" with the uppercased form
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", expected + " /api/v1/resources", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    captured.set(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

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

        // Use ".*" pattern to match any GET path
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", "GET .*", "ruleBased", 0.0, false));

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    captured.set(req);
                    return emptyBulkResponse();
                },
                TEST_CONSISTENCY);

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
    // C3 — contains vs equals on relation check
    // -------------------------------------------------------------------------

    @Test
    void query_ruleBasedWithNonMatchingRelationContainingSubstring_doesNotTreatAsRequiredPermission() {
        // A relation that merely contains the substring "required_permission" but is not equal to it.
        // The old `contains` check would have triggered the required-permission logic for this.
        String fakeRelation = "not_a_required_permission";
        String hashedPerm = base64("fe.billing.read");

        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship(fakeRelation, hashedPerm, "GET /api/.*", "ruleBased", 0.0, false));

        // User does NOT have the permission — but the relation is not "required_permission",
        // so the required-permission gate must be skipped entirely and the rule falls through
        // to the SpiceDB bulk check.
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> routes,
                req -> {
                    bulkCalled.set(true);
                    return responseWith(
                            permissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION));
                },
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                // User has no permissions matching "fe.billing.read"
                new UserSubjectContext("user-1", "tenant-1", List.of(), Map.of()),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(),
                "relation containing 'required_permission' as substring must NOT block; "
                        + "only exact 'required_permission' should trigger the permission gate");
        assertTrue(bulkCalled.get(), "SpiceDB bulk check must be called when relation is not exact match");
    }

    // -------------------------------------------------------------------------
    // C4 — missing policy_type defaults to deny
    // -------------------------------------------------------------------------

    @Test
    void query_missingPolicyType_returnsAllowed() {
        // Build a route relationship with no "policy_type" field in the caveat context.
        Struct.Builder ctx = Struct.newBuilder()
                .putFields("pattern",    Value.newBuilder().setStringValue("GET /api/.*").build())
                .putFields("priority",   Value.newBuilder().setNumberValue(0.0).build())
                .putFields("monitoring", Value.newBuilder().setBoolValue(false).build());
        // Intentionally omit "policy_type"

        ContextualizedCaveat caveat = ContextualizedCaveat.newBuilder()
                .setCaveatName("route_caveat")
                .setContext(ctx.build())
                .build();

        Relationship rel = Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                        .setObjectType("frontegg_route")
                        .setObjectId("route-resource-id")
                        .build())
                .setRelation("entitled")
                .setSubject(SubjectReference.newBuilder()
                        .setObject(ObjectReference.newBuilder()
                                .setObjectType("frontegg_user")
                                .setObjectId("some-id")
                                .build())
                        .build())
                .setOptionalCaveat(caveat)
                .build();

        ReadRelationshipsResponse response = ReadRelationshipsResponse.newBuilder()
                .setRelationship(rel)
                .build();

        RouteSpiceDBQuery query = new RouteSpiceDBQuery(
                req -> List.of(response),
                req -> emptyBulkResponse(),
                TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));

        assertTrue(result.result(),
                "missing policy_type must default to 'allow' (aligned with JS SDK behaviour)");
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link RouteSpiceDBQuery} with a "ruleBased" route matching everything, backed
     * by a canned {@link BulkPermissionsExecutor} that returns a response with the given items.
     */
    private static RouteSpiceDBQuery ruleBasedQueryWith(CheckBulkPermissionsResponseItem... items) {
        CheckBulkPermissionsResponse response = responseWith(items);
        // match-all pattern so all routes fall through to bulk check
        List<ReadRelationshipsResponse> routes = List.of(
                routeRelationship("entitled", "id", ".*", "ruleBased", 0.0, false));
        return new RouteSpiceDBQuery(req -> routes, req -> response, TEST_CONSISTENCY);
    }

    /** Package-visible convenience: builds a ruleBased relationship matching any route. */
    static ReadRelationshipsResponse ruleBasedRelationship(String pattern, double priority) {
        return routeRelationship("view", "subj", pattern, "ruleBased", priority, false);
    }

    /**
     * Builds a {@link ReadRelationshipsResponse} representing a single frontegg_route relationship
     * with the given caveat context fields.
     *
     * @param relation   the relationship relation string (e.g. "entitled", "required_permission")
     * @param subjectId  the subject object ID
     * @param pattern    the regex pattern stored in the caveat context
     * @param policyType the policy_type value ("allow", "deny", "ruleBased")
     * @param priority   the numeric priority
     * @param monitoring whether the monitoring flag is set
     */
    static ReadRelationshipsResponse routeRelationship(
            String relation,
            String subjectId,
            String pattern,
            String policyType,
            double priority,
            boolean monitoring) {

        Struct.Builder ctx = Struct.newBuilder()
                .putFields("pattern",     Value.newBuilder().setStringValue(pattern).build())
                .putFields("policy_type", Value.newBuilder().setStringValue(policyType).build())
                .putFields("priority",    Value.newBuilder().setNumberValue(priority).build())
                .putFields("monitoring",  Value.newBuilder().setBoolValue(monitoring).build());

        ContextualizedCaveat caveat = ContextualizedCaveat.newBuilder()
                .setCaveatName("route_caveat")
                .setContext(ctx.build())
                .build();

        Relationship rel = Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder()
                        .setObjectType("frontegg_route")
                        .setObjectId("route-resource-id")
                        .build())
                .setRelation(relation)
                .setSubject(SubjectReference.newBuilder()
                        .setObject(ObjectReference.newBuilder()
                                .setObjectType("frontegg_user")
                                .setObjectId(subjectId)
                                .build())
                        .build())
                .setOptionalCaveat(caveat)
                .build();

        return ReadRelationshipsResponse.newBuilder()
                .setRelationship(rel)
                .build();
    }

    private static CheckBulkPermissionsResponse responseWith(
            CheckBulkPermissionsResponseItem... items) {
        CheckBulkPermissionsResponse.Builder builder = CheckBulkPermissionsResponse.newBuilder();
        for (CheckBulkPermissionsResponseItem item : items) {
            builder.addPairs(CheckBulkPermissionsPair.newBuilder().setItem(item).build());
        }
        return builder.build();
    }

    private static CheckBulkPermissionsResponse emptyBulkResponse() {
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
