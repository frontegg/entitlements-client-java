package com.frontegg.sdk.entitlements.e2e;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.EntitlementsClientFactory;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.config.ConsistencyPolicy;
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
        schemaWriter.writeSchema();
        schemaWriter.writeRelationships();
        schemaWriter.writeCaveatRelationships();

        ClientConfiguration config = ClientConfiguration.builder()
                .engineEndpoint(endpoint)
                .engineToken(token)
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
