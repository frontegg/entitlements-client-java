package com.frontegg.sdk.entitlements.integration;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.config.ConsistencyPolicy;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;

import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link EntitlementsClient} against a real SpiceDB instance.
 *
 * <p>Spins up SpiceDB via Testcontainers, writes a test schema and fixture relationships,
 * then exercises every check type exposed by the public client API:
 * <ul>
 *   <li>Feature checks (user and tenant entitlement)</li>
 *   <li>Permission checks (single and multiple, AND logic)</li>
 *   <li>FGA checks (viewer/editor permissions on a document type)</li>
 *   <li>Route checks (METHOD:PATH routing)</li>
 *   <li>Async API</li>
 *   <li>LookupResources and LookupSubjects</li>
 * </ul>
 *
 * <p>These tests require Docker to be running on the host. Testcontainers will skip them
 * gracefully when Docker is unavailable.
 *
 * <p>Run with: {@code mvn verify -P integration}
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpiceDBIntegrationTest {

    @Container
    static final SpiceDBContainer spicedb = new SpiceDBContainer();

    static EntitlementsClient client;
    static SpiceDBSchemaWriter schemaWriter;

    @BeforeAll
    static void setup() {
        schemaWriter = new SpiceDBSchemaWriter(
                spicedb.getGrpcEndpoint(), spicedb.getPresharedKey());
        schemaWriter.writeSchema();
        schemaWriter.writeRelationships();
        schemaWriter.writeCaveatRelationships();

        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint(spicedb.getGrpcEndpoint())
                .engineToken(spicedb.getPresharedKey())
                .useTls(false)
                .consistencyPolicy(ConsistencyPolicy.FULLY_CONSISTENT)
                .build();

        client = EntitlementsClientFactory.create(config);
    }

    @AfterAll
    static void teardown() {
        if (client != null) {
            client.close();
        }
        if (schemaWriter != null) {
            schemaWriter.close();
        }
    }

    // -------------------------------------------------------------------------
    // Feature checks
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void featureCheck_userEntitled_returnsAllowed() {
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("premium"));
        assertTrue(result.result(), "user-1 should be entitled to feature 'premium' via user relationship");
        assertFalse(result.monitoring(), "monitoring mode should be off");
    }

    @Test
    @Order(2)
    void featureCheck_unknownUserUnknownTenant_returnsDenied() {
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-999", "tenant-999"),
                new FeatureRequestContext("premium"));
        assertFalse(result.result(), "unknown user in unknown tenant should be denied");
    }

    @Test
    @Order(3)
    void featureCheck_unknownUserKnownTenant_returnsAllowed() {
        // tenant-1 has an entitled relationship to "premium", so any user in tenant-1 is allowed
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-999", "tenant-1"),
                new FeatureRequestContext("premium"));
        assertTrue(result.result(), "unknown user in tenant-1 should be allowed via tenant relationship");
    }

    @Test
    @Order(4)
    void featureCheck_nonExistentFeature_returnsDenied() {
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("non-existent-feature"));
        assertFalse(result.result(), "check for a feature with no relationships should be denied");
    }

    // -------------------------------------------------------------------------
    // Permission checks
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void permissionCheck_singlePermission_entitled_returnsAllowed() {
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("reports:read"));
        assertTrue(result.result(), "user-1 should be entitled to 'reports:read'");
    }

    @Test
    @Order(11)
    void permissionCheck_singlePermission_notLinkedToFeature_returnsAllowed() {
        // 'admin:write' has no parent feature link in SpiceDB — self-sufficient permission
        // → SDK short-circuits to allowed without a CheckBulkPermissions call (JS SDK parity)
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new PermissionRequestContext("admin:write"));
        assertTrue(result.result(), "permission not linked to any feature should be self-sufficient → allowed");
    }

    // -------------------------------------------------------------------------
    // FGA checks (document type)
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    void fgaCheck_viewerPermission_returnsAllowed() {
        // user-1 is viewer of doc-1
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "user-1"),
                new EntityRequestContext("document", "doc-1", "view"));
        assertTrue(result.result(), "user-1 should be able to view doc-1 via viewer relation");
    }

    @Test
    @Order(21)
    void fgaCheck_editPermissionOnViewOnlyDocument_returnsDenied() {
        // user-1 is only viewer of doc-1, not editor — edit should be denied
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "user-1"),
                new EntityRequestContext("document", "doc-1", "edit"));
        assertFalse(result.result(), "user-1 has only viewer on doc-1, not editor — edit should be denied");
    }

    @Test
    @Order(22)
    void fgaCheck_editorCanAlsoView_returnsAllowed() {
        // Schema: permission view = viewer + editor — user-1 as editor of doc-2 can also view it
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "user-1"),
                new EntityRequestContext("document", "doc-2", "view"));
        assertTrue(result.result(), "editor relation implies view permission via schema union");
    }

    @Test
    @Order(23)
    void fgaCheck_editorPermission_returnsAllowed() {
        // user-1 is editor of doc-2 — edit should be allowed
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "user-1"),
                new EntityRequestContext("document", "doc-2", "edit"));
        assertTrue(result.result(), "user-1 is editor of doc-2 — edit should be allowed");
    }

    @Test
    @Order(24)
    void fgaCheck_unknownUser_returnsDenied() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "user-999"),
                new EntityRequestContext("document", "doc-1", "view"));
        assertFalse(result.result(), "unknown user should have no permissions on doc-1");
    }

    // -------------------------------------------------------------------------
    // Route checks
    // -------------------------------------------------------------------------

    @Test
    @Order(30)
    void routeCheck_entitledRoute_returnsAllowed() {
        // RouteSpiceDBQuery formats key as "METHOD:PATH" then base64-encodes — fixture matches
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/reports"));
        assertTrue(result.result(), "user-1 should be entitled to GET:/api/v1/reports");
    }

    @Test
    @Order(31)
    void routeCheck_differentMethodSamePath_returnsDenied() {
        // Only GET was set up — POST should not be found
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("POST", "/api/v1/reports"));
        assertFalse(result.result(), "POST:/api/v1/reports was not set up — should be denied");
    }

    @Test
    @Order(32)
    void routeCheck_unknownRoute_returnsDenied() {
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("user-1", "tenant-1"),
                new RouteRequestContext("GET", "/api/v1/admin"));
        assertFalse(result.result(), "unknown route should be denied");
    }

    // -------------------------------------------------------------------------
    // Async API
    // -------------------------------------------------------------------------

    @Test
    @Order(40)
    void asyncCheck_featureCheck_entitled_returnsAllowed() throws Exception {
        CompletableFuture<EntitlementsResult> future = client.isEntitledToAsync(
                new UserSubjectContext("user-1", "tenant-1"),
                new FeatureRequestContext("premium"));
        EntitlementsResult result = future.get();
        assertTrue(result.result(), "async feature check should return allowed for user-1/premium");
    }

    @Test
    @Order(41)
    void asyncCheck_featureCheck_notEntitled_returnsDenied() throws Exception {
        CompletableFuture<EntitlementsResult> future = client.isEntitledToAsync(
                new UserSubjectContext("user-999", "tenant-999"),
                new FeatureRequestContext("premium"));
        EntitlementsResult result = future.get();
        assertFalse(result.result(), "async feature check should return denied for unknown user");
    }

    // -------------------------------------------------------------------------
    // LookupResources
    // -------------------------------------------------------------------------

    @Test
    @Order(50)
    void lookupResources_userCanViewDocuments_returnsMatchingIds() {
        // user-1 is viewer of doc-1 and editor (implies view) of doc-2
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "user-1", "view", "document"));
        assertNotNull(result);
        assertFalse(result.entityIds().isEmpty(),
                "user-1 should have at least one viewable document");
        assertTrue(result.entityIds().contains("doc-1"),
                "doc-1 should appear — user-1 is explicit viewer");
        assertTrue(result.entityIds().contains("doc-2"),
                "doc-2 should appear — editor implies view via schema union");
    }

    @Test
    @Order(51)
    void lookupResources_unknownUser_returnsEmpty() {
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "user-999", "view", "document"));
        assertNotNull(result);
        assertTrue(result.entityIds().isEmpty(),
                "unknown user should have no viewable documents");
    }

    @Test
    @Order(52)
    void lookupResources_editPermission_returnsOnlyEditableDocuments() {
        // user-1 can only edit doc-2 (editor relation); doc-1 has viewer relation (no edit)
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "user-1", "edit", "document"));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("doc-2"),
                "doc-2 should appear — user-1 is editor");
        assertFalse(result.entityIds().contains("doc-1"),
                "doc-1 should not appear — user-1 is only viewer there, not editor");
    }

    // -------------------------------------------------------------------------
    // LookupSubjects
    // -------------------------------------------------------------------------

    @Test
    @Order(60)
    void lookupSubjects_viewersOfDoc1_containsUser1() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "doc-1", "view", "frontegg_user"));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("user-1"),
                "user-1 should be among the viewers of doc-1");
    }

    @Test
    @Order(61)
    void lookupSubjects_editorsOfDoc1_returnsEmpty() {
        // No user has editor relation on doc-1 — only viewer
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "doc-1", "edit", "frontegg_user"));
        assertNotNull(result);
        assertFalse(result.entityIds().contains("user-1"),
                "user-1 is not an editor of doc-1 — should not appear");
    }

    @Test
    @Order(62)
    void lookupSubjects_editorsOfDoc2_containsUser1() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "doc-2", "edit", "frontegg_user"));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("user-1"),
                "user-1 should appear as editor of doc-2");
    }

    @Test
    @Order(63)
    void lookupSubjects_unknownDocument_returnsEmpty() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "doc-999", "view", "frontegg_user"));
        assertNotNull(result);
        assertTrue(result.entityIds().isEmpty(),
                "unknown document should have no subjects");
    }

    // -------------------------------------------------------------------------
    // Caveat-based FGA checks (time-gated access — ported from Node.js demo)
    // -------------------------------------------------------------------------

    @Test
    @Order(70)
    void caveatFga_timDirectReader_entitledAfterActiveFrom() {
        // Tim is direct reader of Jan salary doc with activeFrom=2026-01-01
        // Checking at 2026-01-01 should be entitled
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Jan", "read_doc",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(result.result(),
                "Tim should be entitled to read Jan salary doc at 2026-01-01 (activeFrom)");
    }

    @Test
    @Order(71)
    void caveatFga_timDirectReader_deniedBeforeActiveFrom() {
        // Tim is direct reader of Feb salary doc with activeFrom=2026-02-01
        // Checking at 2026-01-01 should be denied (before activeFrom)
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Feb", "read_doc",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertFalse(result.result(),
                "Tim should be denied Feb salary doc at 2026-01-01 (before activeFrom of 2026-02-01)");
    }

    @Test
    @Order(72)
    void caveatFga_timDirectReader_entitledAtActiveFrom() {
        // Tim is direct reader of Feb salary doc with activeFrom=2026-02-01
        // Checking at 2026-02-01 should be entitled
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Feb", "read_doc",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertTrue(result.result(),
                "Tim should be entitled to read Feb salary doc at 2026-02-01 (exact activeFrom)");
    }

    @Test
    @Order(73)
    void caveatFga_timDirectReader_marchDocEntitledAtMarch() {
        // Tim is direct reader of Mar salary doc with activeFrom=2026-03-01
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Mar", "read_doc",
                        Instant.parse("2026-03-01T00:00:00Z")));
        assertTrue(result.result(),
                "Tim should be entitled to read Mar salary doc at 2026-03-01");
    }

    @Test
    @Order(74)
    void caveatFga_timDirectReader_marchDocDeniedBeforeMarch() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Mar", "read_doc",
                        Instant.parse("2026-02-15T00:00:00Z")));
        assertFalse(result.result(),
                "Tim should be denied Mar salary doc at 2026-02-15 (before activeFrom)");
    }

    // -------------------------------------------------------------------------
    // Permission inheritance through folder (Alice reads via folder membership)
    // -------------------------------------------------------------------------

    @Test
    @Order(75)
    void caveatFga_aliceInheritance_canReadDocViaFolder() {
        // Alice is reader of "salaries" folder (no caveat)
        // Jan salary doc has "salaries" folder as parent with activeFrom=2026-01-01
        // At 2026-02-01, the parent relation is active → Alice inherits read_doc
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Alice"),
                new EntityRequestContext("document", "Tim's_salary_Jan", "read_doc",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertTrue(result.result(),
                "Alice should read Jan salary doc via folder inheritance at 2026-02-01");
    }

    @Test
    @Order(76)
    void caveatFga_aliceInheritance_deniedBeforeParentActiveFrom() {
        // Feb salary doc parent has activeFrom=2026-02-01
        // At 2026-01-15, the parent relation is not yet active → Alice cannot inherit
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Alice"),
                new EntityRequestContext("document", "Tim's_salary_Feb", "read_doc",
                        Instant.parse("2026-01-15T00:00:00Z")));
        assertFalse(result.result(),
                "Alice should be denied Feb salary doc at 2026-01-15 (parent not yet active)");
    }

    // -------------------------------------------------------------------------
    // Time-filtered lookupResources
    // -------------------------------------------------------------------------

    @Test
    @Order(80)
    void lookupResources_withAt_returnsOnlyTimeValidDocs() {
        // Tim at 2026-01-01 should only be able to read the Jan salary doc
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "Tim", "read_doc", "document",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim's_salary_Jan"),
                "Jan doc should be in results at 2026-01-01");
        assertFalse(result.entityIds().contains("Tim's_salary_Feb"),
                "Feb doc should NOT be in results at 2026-01-01");
        assertFalse(result.entityIds().contains("Tim's_salary_Mar"),
                "Mar doc should NOT be in results at 2026-01-01");
    }

    @Test
    @Order(81)
    void lookupResources_withAt_february_returnsJanAndFeb() {
        // Tim at 2026-02-01 should be able to read Jan and Feb salary docs
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "Tim", "read_doc", "document",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim's_salary_Jan"),
                "Jan doc should be in results at 2026-02-01");
        assertTrue(result.entityIds().contains("Tim's_salary_Feb"),
                "Feb doc should be in results at 2026-02-01");
        assertFalse(result.entityIds().contains("Tim's_salary_Mar"),
                "Mar doc should NOT be in results at 2026-02-01");
    }

    @Test
    @Order(82)
    void lookupResources_withAt_march_returnsAllThree() {
        // Tim at 2026-03-01 should be able to read all three salary docs
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "Tim", "read_doc", "document",
                        Instant.parse("2026-03-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim's_salary_Jan"),
                "Jan doc should be in results at 2026-03-01");
        assertTrue(result.entityIds().contains("Tim's_salary_Feb"),
                "Feb doc should be in results at 2026-03-01");
        assertTrue(result.entityIds().contains("Tim's_salary_Mar"),
                "Mar doc should be in results at 2026-03-01");
    }

    // -------------------------------------------------------------------------
    // Time-filtered lookupSubjects
    // -------------------------------------------------------------------------

    @Test
    @Order(85)
    void lookupSubjects_withAt_janDoc_returnsTimAndAlice() {
        // At 2026-02-01: Tim is direct reader (activeFrom=Jan), Alice inherits via folder (parent activeFrom=Jan)
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "Tim's_salary_Jan", "read_doc", "frontegg_user",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim"),
                "Tim should be a reader of Jan salary doc at 2026-02-01");
        assertTrue(result.entityIds().contains("Alice"),
                "Alice should be a reader of Jan salary doc at 2026-02-01 via folder inheritance");
    }

    @Test
    @Order(86)
    void lookupSubjects_withAt_febDocBeforeFeb_excludesTimAndAlice() {
        // At 2026-01-15: Feb doc has activeFrom=2026-02-01 for both Tim's reader and folder parent
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "Tim's_salary_Feb", "read_doc", "frontegg_user",
                        Instant.parse("2026-01-15T00:00:00Z")));
        assertNotNull(result);
        assertFalse(result.entityIds().contains("Tim"),
                "Tim should NOT be a reader of Feb salary doc at 2026-01-15");
        assertFalse(result.entityIds().contains("Alice"),
                "Alice should NOT be a reader of Feb salary doc at 2026-01-15");
    }

    // -------------------------------------------------------------------------
    // Client lifecycle
    // -------------------------------------------------------------------------

    @Test
    @Order(99)
    void clientClose_doesNotThrow() {
        // Build a separate short-lived client so the shared test client is not invalidated
        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint(spicedb.getGrpcEndpoint())
                .engineToken(spicedb.getPresharedKey())
                .useTls(false)
                .consistencyPolicy(ConsistencyPolicy.FULLY_CONSISTENT)
                .build();
        EntitlementsClient ephemeral = EntitlementsClientFactory.create(config);

        // First close — must not throw
        assertDoesNotThrow(ephemeral::close, "close() should not throw");

        // Second close — idempotent, must not throw either
        assertDoesNotThrow(ephemeral::close, "close() called a second time should be idempotent and not throw");
    }
}
