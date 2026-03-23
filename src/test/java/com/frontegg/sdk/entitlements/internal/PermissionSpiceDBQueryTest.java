package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsPair;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckBulkPermissionsResponseItem;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.SubjectReference;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    private static final Supplier<Consistency> TEST_CONSISTENCY =
            () -> Consistency.newBuilder().setMinimizeLatency(true).build();

    /** Permission key used across tests that need to reach the SpiceDB call path. */
    private static final String REPORTS_READ = "reports:read";

    // -------------------------------------------------------------------------
    // Permissionship outcome mapping
    // -------------------------------------------------------------------------

    @Test
    void query_userEntitled_returnsAllowed() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "user entitled → result must be true");
        assertFalse(result.monitoring(), "monitoring must be false for normal check");
    }

    @Test
    void query_tenantEntitledUserDenied_returnsAllowed() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "tenant entitled → result must be true even if user denied");
    }

    @Test
    void query_userConditionalPermission_returnsDenied() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_tenantConditionalPermission_returnsDenied() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_bothDenied_returnsDenied() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "both denied → result must be false");
    }

    // -------------------------------------------------------------------------
    // Request construction — 2 items per permission key
    // -------------------------------------------------------------------------

    @Test
    void query_requestConstruction_correctResourceTypeAndRelation() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        PermissionSpiceDBQuery query = queryWith(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-abc", "tenant-xyz", List.of("my-permission"), Map.of()),
                new PermissionRequestContext("my-permission"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(2, request.getItemsCount(), "must send exactly 2 items for a single key");

        // tenant item is always first (index 0); user item is second (index 1) when userId present
        CheckBulkPermissionsRequestItem tenantItem = request.getItems(0);
        assertEquals("frontegg_tenant", tenantItem.getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), tenantItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_permission", tenantItem.getResource().getObjectType());
        assertEquals(base64("my-permission"), tenantItem.getResource().getObjectId());
        assertEquals("access", tenantItem.getPermission());

        CheckBulkPermissionsRequestItem userItem = request.getItems(1);
        assertEquals("frontegg_user", userItem.getSubject().getObject().getObjectType());
        assertEquals(base64("user-abc"), userItem.getSubject().getObject().getObjectId());
        assertEquals("frontegg_permission", userItem.getResource().getObjectType(),
                "resource type must be frontegg_permission, not frontegg_feature");
        assertEquals(base64("my-permission"), userItem.getResource().getObjectId());
        assertEquals("access", userItem.getPermission());
    }

    @Test
    void query_nullUserId_onlyTenantItemSent() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        PermissionSpiceDBQuery query = queryWith(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext(null, "tenant-xyz", List.of("my-permission"), Map.of()),
                new PermissionRequestContext("my-permission"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(1, request.getItemsCount(), "only tenant item must be sent when userId is null");
        assertEquals("frontegg_tenant", request.getItems(0).getSubject().getObject().getObjectType());
        assertEquals(base64("tenant-xyz"), request.getItems(0).getSubject().getObject().getObjectId());
    }

    @Test
    void query_blankUserId_onlyTenantItemSent() {
        AtomicReference<CheckBulkPermissionsRequest> captured = new AtomicReference<>();

        PermissionSpiceDBQuery query = queryWith(req -> {
            captured.set(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("   ", "tenant-xyz", List.of("my-permission"), Map.of()),
                new PermissionRequestContext("my-permission"));

        CheckBulkPermissionsRequest request = captured.get();
        assertNotNull(request);
        assertEquals(1, request.getItemsCount(), "only tenant item must be sent when userId is blank");
        assertEquals("frontegg_tenant", request.getItems(0).getSubject().getObject().getObjectType());
    }

    @Test
    void query_nullUserId_tenantEntitled_returnsAllowed() {
        // When userId is null only the tenant item is sent; if tenant is entitled result must be true.
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(
                req -> responseForRequest(req,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION),
                hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext(null, "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "tenant entitled with null userId → result must be true");
    }

    @Test
    void query_nullUserId_tenantDenied_returnsDenied() {
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(
                req -> responseForRequest(req,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext(null, "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "tenant denied with null userId → result must be false");
    }

    // -------------------------------------------------------------------------
    // Caveat context
    // -------------------------------------------------------------------------

    @Test
    void query_withAttributes_caveatContextAttached() {
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        PermissionSpiceDBQuery query = queryWith(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1",
                        List.of(REPORTS_READ),
                        Map.of("plan", "enterprise", "active_at", "2026-01-01")),
                new PermissionRequestContext("reports:read"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext(),
                    "caveat context must be present when attributes are non-empty");
            com.google.protobuf.Struct ctx = item.getContext();
            assertTrue(ctx.containsFields("user_context"),
                    "attributes must be nested under 'user_context' (targeting caveat shape)");
            com.google.protobuf.Struct uc = ctx.getFieldsOrThrow("user_context").getStructValue();
            assertEquals("enterprise", uc.getFieldsOrThrow("plan").getStringValue(),
                    "user attribute 'plan' must be present inside user_context");
            assertEquals("2026-01-01", uc.getFieldsOrThrow("active_at").getStringValue(),
                    "user attribute 'active_at' must be present inside user_context");
        }
    }

    @Test
    void query_caveatContext_hasUserContextWrapper() {
        // Regression test for the bug reported by QA:
        // PermissionSpiceDBQuery was using CaveatContextBuilder.build() (flat struct) instead of
        // buildForTargetingCaveat() (user_context-wrapped struct with `now`).
        // The SpiceDB `targeting` caveat requires the `user_context` wrapper — without it SpiceDB
        // returns PERMISSIONSHIP_CONDITIONAL_PERMISSION which the code treats as denied (fail-closed),
        // causing permissions linked via feature → plan to incorrectly return false.
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        PermissionSpiceDBQuery query = queryWith(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1",
                        List.of(REPORTS_READ), Map.of("plan", "enterprise")),
                new PermissionRequestContext("reports:read"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext(), "caveat context must be present");
            com.google.protobuf.Struct ctx = item.getContext();
            assertTrue(ctx.containsFields("user_context"),
                    "caveat context must be wrapped under 'user_context' key (targeting caveat shape) — "
                    + "got fields: " + ctx.getFieldsMap().keySet());
            com.google.protobuf.Struct userCtxStruct =
                    ctx.getFieldsOrThrow("user_context").getStructValue();
            assertTrue(userCtxStruct.containsFields("now"),
                    "user_context must contain 'now' timestamp field required by targeting caveat");
        }
    }

    @Test
    void query_withoutAttributes_caveatContextStillHasUserContextWrapper() {
        // buildForTargetingCaveat always returns a non-null Struct (always includes `now`),
        // so context is present even when no user attributes are supplied.
        List<CheckBulkPermissionsRequest> requests = new ArrayList<>();

        PermissionSpiceDBQuery query = queryWith(req -> {
            requests.add(req);
            return emptyResponse();
        });

        query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertEquals(1, requests.size());
        for (CheckBulkPermissionsRequestItem item : requests.get(0).getItemsList()) {
            assertTrue(item.hasContext(),
                    "caveat context must be present (targeting caveat always requires user_context with now)");
            assertTrue(item.getContext().containsFields("user_context"),
                    "caveat context must have user_context wrapper even when no attributes are supplied");
            assertTrue(item.getContext().getFieldsOrThrow("user_context")
                            .getStructValue().containsFields("now"),
                    "user_context must contain 'now' field");
        }
    }

    // -------------------------------------------------------------------------
    // Client-side permission cache (Step 1)
    // -------------------------------------------------------------------------

    @Test
    void query_withPermissionsCache_exactMatch_proceedsToSpiceDB() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of("reports:read"), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "exact match in cache + SpiceDB allows → result must be true");
        assertTrue(bulkCalled.get(), "bulk check must be called when permission passes cache");
    }

    @Test
    void query_withPermissionsCache_wildcardMatch_proceedsToSpiceDB() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        // "reports:*" matches "reports:read" — colon is not special, wildcard covers the suffix
        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of("reports:*"), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "wildcard match in cache → should proceed to SpiceDB and be allowed");
        assertTrue(bulkCalled.get(), "bulk check must be called when permission passes wildcard cache");
    }

    @Test
    void query_withPermissionsCache_noMatch_returnsDeniedWithoutSpiceDB() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return emptyResponse();
        }, hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of("billing.*"), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "no cache match → result must be denied");
        assertFalse(bulkCalled.get(), "SpiceDB must NOT be called when permission fails cache check");
    }

    @Test
    void query_noPermissionsList_returnsDeniedWithoutSpiceDB() {
        // No permissions list → deny immediately (JS SDK parity: permissions?.some() ?? false).
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return emptyResponse();
        }, hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "no permissions list → must deny");
        assertFalse(bulkCalled.get(), "SpiceDB must NOT be called when no permissions list is provided");
    }

    @Test
    void query_noPermissionsProvided_returnsDeniedWithoutSpiceDB() {
        // JS SDK parity: permissions?.some(...) ?? false
        // When no permissions list is supplied, hasPermission returns false → deny immediately.
        // Java was incorrectly skipping the check and falling through to SpiceDB (which could
        // return true), diverging from JS/React SDK behaviour.
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("fe.x.write"));

        assertFalse(result.result(), "no permissions list → must deny without SpiceDB call (JS SDK parity)");
        assertFalse(bulkCalled.get(), "SpiceDB must NOT be called when no permissions list is provided");
    }

    @Test
    void query_emptyPermissionsList_returnsDeniedWithoutSpiceDB() {
        // Explicit empty list is the same signal as absent list in JS SDK: deny immediately.
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(), Map.of()),
                new PermissionRequestContext("fe.x.write"));

        assertFalse(result.result(), "empty permissions list → must deny without SpiceDB call (JS SDK parity)");
        assertFalse(bulkCalled.get(), "SpiceDB must NOT be called when permissions list is empty");
    }

    // -------------------------------------------------------------------------
    // Feature-linking check (Step 2)
    // -------------------------------------------------------------------------

    @Test
    void query_notLinkedToFeature_returnsAllowedWithoutCheckBulk() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        // Lookup returns empty → not linked to any feature
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return emptyResponse();
        }, noFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "not linked to feature → should be allowed immediately");
        assertFalse(bulkCalled.get(), "CheckBulkPermissions must NOT be called when not linked to feature");
    }

    @Test
    void query_linkedToFeature_proceedsToCheckBulk() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        // Lookup returns one result → linked to a feature
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "linked to feature + SpiceDB allows → result must be true");
        assertTrue(bulkCalled.get(), "CheckBulkPermissions must be called when linked to feature");
    }

    // -------------------------------------------------------------------------
    // Feature-link cache — only true is cached; false is never cached
    // -------------------------------------------------------------------------

    @Test
    void query_featureLinkCachedTrue_doesNotCallLookupAgain() {
        AtomicReference<Integer> lookupCallCount = new AtomicReference<>(0);
        LookupSubjectsExecutor countingLookup = req -> {
            lookupCallCount.updateAndGet(c -> c + 1);
            // Always return linked (true)
            return List.of(com.authzed.api.v1.LookupSubjectsResponse.newBuilder().build()).iterator();
        };

        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(
                req -> responseForRequest(req,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                countingLookup,
                TEST_CONSISTENCY);

        // First call — should call LookupSubjects once, cache the "true" result.
        query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));
        assertEquals(1, lookupCallCount.get(), "LookupSubjects must be called on the first request");

        // Second call with the same permission key — should use cache; LookupSubjects not called again.
        query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));
        assertEquals(1, lookupCallCount.get(),
                "LookupSubjects must NOT be called again when cached true is still valid");
    }

    @Test
    void query_featureLinkCacheExpired_rechecksSpiceDB() throws Exception {
        AtomicReference<Integer> lookupCallCount = new AtomicReference<>(0);
        LookupSubjectsExecutor countingLookup = req -> {
            lookupCallCount.updateAndGet(c -> c + 1);
            return List.of(com.authzed.api.v1.LookupSubjectsResponse.newBuilder().build()).iterator();
        };

        // Use a very short TTL so we can expire the cache quickly in a test.
        long shortTtlMs = 50L;
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(
                req -> responseForRequest(req,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION),
                countingLookup,
                TEST_CONSISTENCY,
                shortTtlMs);

        // First call — populates cache.
        query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));
        assertEquals(1, lookupCallCount.get(), "LookupSubjects must be called on first request");

        // Wait for the cache entry to expire.
        Thread.sleep(shortTtlMs + 20);

        // Second call after expiry — must re-check SpiceDB.
        query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(REPORTS_READ), Map.of()),
                new PermissionRequestContext("reports:read"));
        assertEquals(2, lookupCallCount.get(),
                "LookupSubjects must be called again after cache TTL expires");
    }

    // -------------------------------------------------------------------------
    // Helper factory methods
    // -------------------------------------------------------------------------

    /** Lookup stub that simulates permission IS linked to a feature. */
    private static LookupSubjectsExecutor hasFeatures() {
        return req -> List.of(
                com.authzed.api.v1.LookupSubjectsResponse.newBuilder().build()
        ).iterator();
    }

    /** Lookup stub that simulates permission is NOT linked to any feature. */
    private static LookupSubjectsExecutor noFeatures() {
        return req -> Collections.emptyIterator();
    }

    /**
     * Builds a response that mirrors the items from the request, assigning permissionships
     * in round-robin order (tenantPermissionship for even indices, userPermissionship for odd).
     * This simulates the SpiceDB response pattern: [tenant→key, user→key, tenant→key2, ...].
     * Tenant is always added first; user item is added second (only when userId is present).
     */
    private static CheckBulkPermissionsResponse responseForRequest(
            CheckBulkPermissionsRequest req,
            CheckPermissionResponse.Permissionship userPermissionship,
            CheckPermissionResponse.Permissionship tenantPermissionship) {
        CheckBulkPermissionsResponse.Builder responseBuilder = CheckBulkPermissionsResponse.newBuilder();
        List<CheckBulkPermissionsRequestItem> items = req.getItemsList();
        for (int i = 0; i < items.size(); i++) {
            // Even index = tenant item, odd index = user item (tenant is added first)
            CheckPermissionResponse.Permissionship p = (i % 2 == 0) ? tenantPermissionship : userPermissionship;
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

    /**
     * Builds a {@link PermissionSpiceDBQuery} with the given bulk executor and a
     * features-linked lookup stub (simulates the case where the permission IS linked to
     * a feature, so the full CheckBulkPermissions path is always exercised).
     */
    private static PermissionSpiceDBQuery queryWith(BulkPermissionsExecutor bulkExecutor) {
        // Non-empty iterator → features linked → proceeds to CheckBulkPermissions
        LookupSubjectsExecutor hasFeatures = req -> List.of(
                com.authzed.api.v1.LookupSubjectsResponse.newBuilder().build()
        ).iterator();
        return new PermissionSpiceDBQuery(bulkExecutor, hasFeatures, TEST_CONSISTENCY);
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
