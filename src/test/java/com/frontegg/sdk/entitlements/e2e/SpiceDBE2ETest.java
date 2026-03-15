package com.frontegg.sdk.entitlements.e2e;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.integration.SpiceDBSchemaWriter;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests that run against an external SpiceDB instance (e.g. via docker-compose).
 *
 * <p>These tests exercise the same caveat/inheritance scenarios as the Node.js SDK demo,
 * validating full parity between the Java and Node.js SDKs.
 *
 * <p>Run with: {@code mvn verify -P e2e -Dspicedb.endpoint=localhost:50051 -Dspicedb.token=spicedb}
 * <p>Or use the convenience script: {@code ./e2e/run-e2e.sh}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpiceDBE2ETest {

    static EntitlementsClient client;
    static SpiceDBSchemaWriter schemaWriter;

    @BeforeAll
    static void setup() {
        String endpoint = System.getProperty("spicedb.endpoint", "localhost:50051");
        String token = System.getProperty("spicedb.token", "spicedb");

        // Seed schema and relationships via gRPC (idempotent)
        schemaWriter = new SpiceDBSchemaWriter(endpoint, token);
        try {
            schemaWriter.writeSchema();
            System.out.println("=== DEBUG: Schema written OK ===");
        } catch (Exception e) {
            System.out.println("=== DEBUG: Schema write FAILED: " + e.getMessage() + " ===");
            throw e;
        }
        try {
            schemaWriter.writeRelationships();
            System.out.println("=== DEBUG: Relationships written OK ===");
        } catch (Exception e) {
            System.out.println("=== DEBUG: Relationships write FAILED: " + e.getMessage() + " ===");
            throw e;
        }
        try {
            schemaWriter.writeCaveatRelationships();
            System.out.println("=== DEBUG: Caveat relationships written OK ===");
        } catch (Exception e) {
            System.out.println("=== DEBUG: Caveat relationships write FAILED: " + e.getMessage() + " ===");
            throw e;
        }

        // Verify schema was actually written
        String schema = schemaWriter.readSchema();
        System.out.println("=== DEBUG: Schema contains 'document': " + schema.contains("document") + " ===");
        System.out.println("=== DEBUG: Schema contains 'active_at': " + schema.contains("active_at") + " ===");

        // Verify relationships were written
        var docRels = schemaWriter.readRelationships("document");
        System.out.println("=== DEBUG: Document relationships count: " + docRels.size() + " ===");
        for (String rel : docRels) {
            System.out.println("=== DEBUG: Rel: " + rel + " ===");
        }
        var folderRels = schemaWriter.readRelationships("folder");
        System.out.println("=== DEBUG: Folder relationships count: " + folderRels.size() + " ===");
        for (String rel : folderRels) {
            System.out.println("=== DEBUG: Rel: " + rel + " ===");
        }

        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint(endpoint)
                .engineToken(token)
                .useTls(false)
                .build();

        client = EntitlementsClientFactory.create(config);

        // Direct check via schema writer stub (bypasses SDK, uses fully_consistent)
        String directResult = schemaWriter.directCheckPermission(
                "frontegg_user", "Tim", "document", "Tim's_salary_Jan",
                "read_doc", "2026-01-01T00:00:00Z");
        System.out.println("=== DEBUG: DIRECT CheckPermission result=" + directResult + " ===");

        String directNoAt = schemaWriter.directCheckPermission(
                "frontegg_user", "Tim", "document", "Tim's_salary_Jan",
                "read_doc", null);
        System.out.println("=== DEBUG: DIRECT CheckPermission without at=" + directNoAt + " ===");

        // SDK check for comparison
        System.out.println("=== DEBUG: Testing SDK Tim read_doc Tim's_salary_Jan at 2026-01-01 ===");
        EntitlementsResult debugResult = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Jan", "read_doc",
                        Instant.parse("2026-01-01T00:00:00Z")));
        System.out.println("=== DEBUG: SDK result=" + debugResult.result() + " ===");

        EntitlementsResult debugResult2 = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Jan", "read_doc", null));
        System.out.println("=== DEBUG: SDK result without at=" + debugResult2.result() + " ===");
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
    // Demo 1: Alice reads Tim's salary via folder inheritance
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void alice_canReadTimsSalaryViaFolderInheritance() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Alice"),
                new EntityRequestContext("document", "Tim's_salary_Jan", "read_doc",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertTrue(result.result(),
                "Alice should read Tim's Jan salary doc via folder inheritance");
    }

    @Test
    @Order(2)
    void alice_cannotReadBeforeParentActivation() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Alice"),
                new EntityRequestContext("document", "Tim's_salary_Feb", "read_doc",
                        Instant.parse("2026-01-15T00:00:00Z")));
        assertFalse(result.result(),
                "Alice should be denied Feb salary doc before parent folder activation");
    }

    // -------------------------------------------------------------------------
    // Demo 2: Tim reads his own salary (direct, time-gated)
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void tim_canReadJanSalaryAtJan() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Jan", "read_doc",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(result.result(),
                "Tim should read Jan salary doc at 2026-01-01");
    }

    @Test
    @Order(11)
    void tim_cannotReadFebSalaryAtJan() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Feb", "read_doc",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertFalse(result.result(),
                "Tim should be denied Feb salary doc at 2026-01-01");
    }

    @Test
    @Order(12)
    void tim_canReadFebSalaryAtFeb() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Feb", "read_doc",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertTrue(result.result(),
                "Tim should read Feb salary doc at 2026-02-01");
    }

    @Test
    @Order(13)
    void tim_canReadMarSalaryAtMar() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("frontegg_user", "Tim"),
                new EntityRequestContext("document", "Tim's_salary_Mar", "read_doc",
                        Instant.parse("2026-03-01T00:00:00Z")));
        assertTrue(result.result(),
                "Tim should read Mar salary doc at 2026-03-01");
    }

    // -------------------------------------------------------------------------
    // Demo 3: Lookup documents Tim can read at different times
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    void lookupResources_timAtJan_onlyJanDoc() {
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "Tim", "read_doc", "document",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim's_salary_Jan"));
        assertFalse(result.entityIds().contains("Tim's_salary_Feb"));
        assertFalse(result.entityIds().contains("Tim's_salary_Mar"));
    }

    @Test
    @Order(21)
    void lookupResources_timAtFeb_janAndFebDocs() {
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "Tim", "read_doc", "document",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim's_salary_Jan"));
        assertTrue(result.entityIds().contains("Tim's_salary_Feb"));
        assertFalse(result.entityIds().contains("Tim's_salary_Mar"));
    }

    @Test
    @Order(22)
    void lookupResources_timAtMar_allThreeDocs() {
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("frontegg_user", "Tim", "read_doc", "document",
                        Instant.parse("2026-03-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim's_salary_Jan"));
        assertTrue(result.entityIds().contains("Tim's_salary_Feb"));
        assertTrue(result.entityIds().contains("Tim's_salary_Mar"));
    }

    // -------------------------------------------------------------------------
    // Demo 4: Lookup users who can read a specific document
    // -------------------------------------------------------------------------

    @Test
    @Order(30)
    void lookupSubjects_janDocAtFeb_returnsTimAndAlice() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "Tim's_salary_Jan", "read_doc",
                        "frontegg_user", Instant.parse("2026-02-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("Tim"),
                "Tim should be a reader of Jan salary doc");
        assertTrue(result.entityIds().contains("Alice"),
                "Alice should be a reader of Jan salary doc via folder");
    }

    @Test
    @Order(31)
    void lookupSubjects_febDocBeforeFeb_returnsNoOne() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("document", "Tim's_salary_Feb", "read_doc",
                        "frontegg_user", Instant.parse("2026-01-15T00:00:00Z")));
        assertNotNull(result);
        assertFalse(result.entityIds().contains("Tim"));
        assertFalse(result.entityIds().contains("Alice"));
    }
}
