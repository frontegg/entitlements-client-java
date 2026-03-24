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
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests that run against an external SpiceDB instance (e.g. via docker-compose).
 *
 * <p>These tests exercise the new schema defined in {@code e2e/e2e-schema-relationship.yaml},
 * covering the frontegg plan→feature→permission hierarchy and custom FGA types
 * (cust_user, cust_group, cust_subscription, cust_document) with {@code active_at} caveats.
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
    // Demo 1: Permission inheritance through plan→feature→permission hierarchy
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void tenant_hasAccessToPermissionViaFeaturePlan() {
        // test-tenant-1 is entitled via plan→feature→permission chain.
        // UserSubjectContext with the permission key in the permissions list allows the SDK to
        // pass through client-side filtering and use buildForTargetingCaveat (which populates
        // user_context["now"]) when calling SpiceDB.
        EntitlementsResult result = client.isEntitledTo(
                new UserSubjectContext("test-user-1", "test-tenant-1",
                        List.of("test-permission-1"), java.util.Map.of()),
                new PermissionRequestContext("test-permission-1"));
        assertTrue(result.result(),
                "test-tenant-1 should have access to test-permission-1 via plan→feature→permission chain");
    }

    // -------------------------------------------------------------------------
    // Demo 2: cust_user accesses cust_document via group→subscription hierarchy
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void custUser_hasAccessToDocViaGroupSubscription() {
        // user-1-r4up1aoO is member of group-1-R1NipP4y
        // group-1-R1NipP4y is parent_group of sub-1-9Xn1Adj8
        // sub-1-9Xn1Adj8 is parent_subscription of doc-1-VTj7UDZ9
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-1-r4up1aoO"),
                new EntityRequestContext("cust_document", "doc-1-VTj7UDZ9", "access", null));
        assertTrue(result.result(),
                "user-1-r4up1aoO should have access to doc-1-VTj7UDZ9 via group→subscription chain");
    }

    @Test
    @Order(11)
    void unknownUser_hasNoAccessToDoc() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "unknown-user"),
                new EntityRequestContext("cust_document", "doc-1-VTj7UDZ9", "access", null));
        assertFalse(result.result(),
                "unknown-user should not have access to doc-1-VTj7UDZ9");
    }

    // -------------------------------------------------------------------------
    // Demo 3: Time-gated cust_document viewer access
    // -------------------------------------------------------------------------

    @Test
    @Order(20)
    void custUser2_canViewDoc2AtJan() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-2-jan"),
                new EntityRequestContext("cust_document", "doc-2-jan", "access",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(result.result(),
                "user-2-jan should be able to view doc-2-jan at 2026-01-01 (activeFrom=2026-01-01)");
    }

    @Test
    @Order(21)
    void custUser2_cannotViewDoc2BeforeJan() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-2-jan"),
                new EntityRequestContext("cust_document", "doc-2-jan", "access",
                        Instant.parse("2025-12-31T23:59:59Z")));
        assertFalse(result.result(),
                "user-2-jan should not be able to view doc-2-jan before 2026-01-01");
    }

    @Test
    @Order(22)
    void custUser2_cannotViewDoc3FebAtJan() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-2-jan"),
                new EntityRequestContext("cust_document", "doc-3-feb", "access",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertFalse(result.result(),
                "user-2-jan should not be able to view doc-3-feb at 2026-01-01 (activeFrom=2026-02-01)");
    }

    @Test
    @Order(23)
    void custUser2_canViewDoc3FebAtFeb() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-2-jan"),
                new EntityRequestContext("cust_document", "doc-3-feb", "access",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertTrue(result.result(),
                "user-2-jan should be able to view doc-3-feb at 2026-02-01 (activeFrom=2026-02-01)");
    }

    // -------------------------------------------------------------------------
    // Demo 4: Subscription inheritance with time-gated parent_subscription
    // -------------------------------------------------------------------------

    @Test
    @Order(30)
    void alice_canAccessDoc4ViaSubscriptionAtJan() {
        // user-3-alice is viewer of sub-2-jan (no caveat)
        // doc-4-alice has parent_subscription sub-2-jan with active_at activeFrom=2026-01-01
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-3-alice"),
                new EntityRequestContext("cust_document", "doc-4-alice", "access",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertTrue(result.result(),
                "user-3-alice should have access to doc-4-alice at 2026-01-01 via sub-2-jan");
    }

    @Test
    @Order(31)
    void alice_cannotAccessDoc4BeforeJan() {
        EntitlementsResult result = client.isEntitledTo(
                new EntitySubjectContext("cust_user", "user-3-alice"),
                new EntityRequestContext("cust_document", "doc-4-alice", "access",
                        Instant.parse("2025-12-31T23:59:59Z")));
        assertFalse(result.result(),
                "user-3-alice should not have access to doc-4-alice before 2026-01-01");
    }

    // -------------------------------------------------------------------------
    // Demo 5: LookupResources for cust_document
    // -------------------------------------------------------------------------

    @Test
    @Order(40)
    void lookupResources_user2AtJan_onlyDoc2() {
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("cust_user", "user-2-jan", "access", "cust_document",
                        Instant.parse("2026-01-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("doc-2-jan"),
                "user-2-jan should see doc-2-jan at 2026-01-01");
        assertFalse(result.entityIds().contains("doc-3-feb"),
                "user-2-jan should not see doc-3-feb at 2026-01-01");
    }

    @Test
    @Order(41)
    void lookupResources_user2AtFeb_doc2AndDoc3() {
        LookupResult result = client.lookupResources(
                new LookupResourcesRequest("cust_user", "user-2-jan", "access", "cust_document",
                        Instant.parse("2026-02-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("doc-2-jan"),
                "user-2-jan should see doc-2-jan at 2026-02-01");
        assertTrue(result.entityIds().contains("doc-3-feb"),
                "user-2-jan should see doc-3-feb at 2026-02-01");
    }

    // -------------------------------------------------------------------------
    // Demo 6: LookupSubjects for cust_document
    // -------------------------------------------------------------------------

    @Test
    @Order(50)
    void lookupSubjects_doc2AtJan_returnsUser2() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("cust_document", "doc-2-jan", "access",
                        "cust_user", Instant.parse("2026-01-01T00:00:00Z")));
        assertNotNull(result);
        assertTrue(result.entityIds().contains("user-2-jan"),
                "user-2-jan should be a subject of doc-2-jan at 2026-01-01");
    }

    @Test
    @Order(51)
    void lookupSubjects_doc3FebBeforeFeb_returnsNone() {
        LookupResult result = client.lookupSubjects(
                new LookupSubjectsRequest("cust_document", "doc-3-feb", "access",
                        "cust_user", Instant.parse("2026-01-15T00:00:00Z")));
        assertNotNull(result);
        assertFalse(result.entityIds().contains("user-2-jan"),
                "user-2-jan should not be a subject of doc-3-feb before 2026-02-01");
    }
}
