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

    // -------------------------------------------------------------------------
    // Permissionship outcome mapping
    // -------------------------------------------------------------------------

    @Test
    void query_userEntitled_returnsAllowed() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
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
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "tenant entitled → result must be true even if user denied");
    }

    @Test
    void query_userConditionalPermission_returnsDenied() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_tenantConditionalPermission_returnsDenied() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_CONDITIONAL_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertFalse(result.result(), "conditional permission → result must be false (fail-closed)");
    }

    @Test
    void query_bothDenied_returnsDenied() {
        PermissionSpiceDBQuery query = queryWith(req ->
                responseForRequest(req, CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION,
                        CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION));

        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
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

        PermissionSpiceDBQuery query = queryWith(req -> {
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

        PermissionSpiceDBQuery query = queryWith(req -> {
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
    void query_emptyPermissionsCache_skipsCacheAndProceedsToSpiceDB() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);
        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        // No permissions list → cache check skipped
        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "no cache → falls through to SpiceDB which allows");
        assertTrue(bulkCalled.get(), "bulk check must be called when no permissions cache is set");
    }

    @Test
    void query_emptyPermissionsInContext_skipsCacheAndProceedsToFeatureLinking() {
        AtomicReference<Boolean> bulkCalled = new AtomicReference<>(false);

        PermissionSpiceDBQuery query = new PermissionSpiceDBQuery(req -> {
            bulkCalled.set(true);
            return responseForRequest(req,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION,
                    CheckPermissionResponse.Permissionship.PERMISSIONSHIP_NO_PERMISSION);
        }, hasFeatures(), TEST_CONSISTENCY);

        // Empty list of permissions (not null, but empty)
        EntitlementsResult result = query.query(
                new UserSubjectContext("user-1", "tenant-1", List.of(), Map.of()),
                new PermissionRequestContext("reports:read"));

        assertTrue(result.result(), "empty permissions list skips cache and falls through");
        assertTrue(bulkCalled.get(), "CheckBulkPermissions must be called after feature-linking");
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
                new UserSubjectContext("user-1", "tenant-1"),
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
                new UserSubjectContext("user-1", "tenant-1"),
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
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));
        assertEquals(1, lookupCallCount.get(), "LookupSubjects must be called on the first request");

        // Second call with the same permission key — should use cache; LookupSubjects not called again.
        query.query(
                new UserSubjectContext("user-1", "tenant-1"),
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
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));
        assertEquals(1, lookupCallCount.get(), "LookupSubjects must be called on first request");

        // Wait for the cache entry to expire.
        Thread.sleep(shortTtlMs + 20);

        // Second call after expiry — must re-check SpiceDB.
        query.query(
                new UserSubjectContext("user-1", "tenant-1"),
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
