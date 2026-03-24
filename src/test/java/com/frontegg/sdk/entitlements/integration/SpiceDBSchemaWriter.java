package com.frontegg.sdk.entitlements.integration;

import com.authzed.api.v1.ContextualizedCaveat;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.PermissionsServiceGrpc;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.RelationshipUpdate;
import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.SubjectReference;
import com.authzed.api.v1.WriteRelationshipsRequest;
import com.authzed.api.v1.WriteSchemaRequest;
import com.authzed.grpcutil.BearerToken;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Writes the test schema and fixture relationships to SpiceDB before integration tests run.
 *
 * <p>All object IDs are Base64 URL-safe encoded (no padding) to match the encoding applied by
 * the SDK's {@code Base64Utils.encode()} method. The route key is formatted as
 * {@code METHOD:PATH} before encoding, mirroring {@code RouteSpiceDBQuery}.
 */
public class SpiceDBSchemaWriter {

    private final ManagedChannel channel;
    private final SchemaServiceGrpc.SchemaServiceBlockingStub schemaStub;
    private final PermissionsServiceGrpc.PermissionsServiceBlockingStub permissionsStub;

    public SpiceDBSchemaWriter(String endpoint, String token) {
        this.channel = ManagedChannelBuilder.forTarget(endpoint)
                .usePlaintext()
                .build();
        BearerToken bearerToken = new BearerToken(token);
        this.schemaStub = SchemaServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(bearerToken);
        this.permissionsStub = PermissionsServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(bearerToken);
    }

    /**
     * Writes the SpiceDB schema covering all resource types used by the integration and e2e tests:
     * frontegg_user, frontegg_tenant, frontegg_feature, frontegg_permission,
     * frontegg_non_linked_permission, frontegg_plan, frontegg_route,
     * and custom FGA types cust_user, cust_group, cust_subscription, cust_document.
     */
    public void writeSchema() {
        String schema = """
                caveat targeting(rules list<list<map<any>>>, expiration timestamp, user_context map<any>) {
                    (rules.size() == 0 || rules.exists(rule,
                      rule.all(condition,
                        condition.attribute in user_context &&
                        condition.negate != (
                          (condition.op == "starts_with"        && condition.value.exists(v,
                            string(user_context[condition.attribute]).startsWith(string(v)))) ||
                          (condition.op == "ends_with"          && condition.value.exists(v,
                            string(user_context[condition.attribute]).endsWith(string(v))))  ||
                          (condition.op == "contains"           && condition.value.exists(v,
                            string(user_context[condition.attribute]).contains(string(v))))  ||
                          (condition.op == "matches"            && string(user_context[condition.attribute]) == string(condition.value)) ||
                          (condition.op == "in_list"            && condition.value.exists(v,
                            string(user_context[condition.attribute]) == (string(v)))) ||
                          (condition.op == "equal"              && double(condition.value) == double(user_context[condition.attribute])) ||
                          (condition.op == "greater_than"       && double(user_context[condition.attribute]) >  double(condition.value)) ||
                          (condition.op == "greater_than_equal" && double(user_context[condition.attribute]) >= double(condition.value)) ||
                          (condition.op == "lower_than"         && double(user_context[condition.attribute]) <  double(condition.value)) ||
                          (condition.op == "lower_than_equal"   && double(user_context[condition.attribute]) <= double(condition.value)) ||
                          (condition.op == "between_numeric"    && condition.value.size() == 2 &&
                            double(user_context[condition.attribute]) >= double(condition.value[0]) &&
                            double(user_context[condition.attribute]) <= double(condition.value[1])) ||
                          (condition.op == "is"                 && bool(user_context[condition.attribute]) == bool(condition.value))     ||
                          (condition.op == "on_or_after"        && timestamp(user_context[condition.attribute]) >= timestamp(condition.value)) ||
                          (condition.op == "on_or_before"       && timestamp(user_context[condition.attribute]) <= timestamp(condition.value)) ||
                          (condition.op == "between_date"       && condition.value.size() == 2 &&
                            timestamp(user_context[condition.attribute]) >= timestamp(condition.value[0]) &&
                            timestamp(user_context[condition.attribute]) <= timestamp(condition.value[1])) ||
                          (condition.op == "on" &&
                            timestamp(user_context[condition.attribute]) >= timestamp(condition.value) &&
                            timestamp(user_context[condition.attribute]) <  timestamp(condition.value) + duration("24h"))
                        )
                      )
                    )) && (rules.size() > 0 || expiration >= timestamp(user_context["now"]))
                }

                caveat route_regex(pattern string, monitoring bool, policy_type string, priority int) {
                  pattern == pattern && monitoring == monitoring && policy_type == policy_type && priority == priority
                }

                caveat active_at(at timestamp, activeFrom any, activeUntil any) {
                  (activeFrom == null || at >= timestamp(activeFrom)) && (activeUntil == null || at <= timestamp(activeUntil))
                }

                definition frontegg_user {}

                definition frontegg_tenant {
                    relation member: frontegg_user

                    permission access = member
                }

                definition frontegg_permission {
                    relation parent: frontegg_feature

                    permission access = parent -> access
                }

                definition frontegg_non_linked_permission {
                    relation granted: frontegg_tenant:*

                    permission access = granted
                }

                definition frontegg_feature {
                    relation granted: frontegg_tenant with targeting | frontegg_user with targeting | frontegg_tenant#member with targeting | frontegg_user:* with targeting | frontegg_tenant:* with targeting
                    relation parent: frontegg_plan

                    permission access = granted + parent -> access
                }

                definition frontegg_plan {
                    relation entitled_tenant: frontegg_tenant with targeting | frontegg_tenant:* with targeting
                    relation entitled_user: frontegg_user with targeting | frontegg_user:* with targeting

                    permission access = entitled_tenant + entitled_user
                }

                definition frontegg_route {
                    relation apply_all_tenants: frontegg_tenant:* with route_regex
                    relation required_permission: frontegg_permission with route_regex | frontegg_non_linked_permission with route_regex
                    relation required_feature: frontegg_feature with route_regex
                    relation required_permission_only: frontegg_permission with route_regex | frontegg_non_linked_permission with route_regex
                    relation required_feature_only: frontegg_feature with route_regex

                    permission feature_access = required_feature.all(access) & required_permission.all(access)
                    permission permission_only_access = required_permission_only.all(access)
                    permission feature_only_access = required_feature_only.all(access)
                    permission access = feature_access + permission_only_access + feature_only_access
                }

                definition cust_document {
                    relation parent_subscription: cust_subscription | cust_subscription with active_at
                    relation viewer: cust_user | cust_user with active_at
                    permission access = parent_subscription->access + viewer
                }

                definition cust_group {
                    relation member: cust_user | cust_user with active_at
                    permission access = member
                }

                definition cust_subscription {
                    relation parent_group: cust_group | cust_group with active_at
                    relation viewer: cust_user | cust_user with active_at
                    permission access = parent_group->access + viewer
                }

                definition cust_user {}
                """;

        schemaStub.writeSchema(WriteSchemaRequest.newBuilder()
                .setSchema(schema)
                .build());
    }

    /**
     * Writes all fixture relationships used by the e2e tests in a single batch request.
     *
     * <p>Sets up the frontegg plan→feature→permission hierarchy and cust_* FGA chain:
     * <ul>
     *   <li>test-permission-1 has parent test-feature-1</li>
     *   <li>test-feature-1 has parent plan f4d30717-...</li>
     *   <li>plan f4d30717-... has entitled_tenant test-tenant-1 (with targeting caveat, empty rules)</li>
     *   <li>cust_group group-1-R1NipP4y has member cust_user user-1-r4up1aoO</li>
     *   <li>cust_subscription sub-1-9Xn1Adj8 has parent_group group-1-R1NipP4y</li>
     *   <li>cust_document doc-1-VTj7UDZ9 has parent_subscription sub-1-9Xn1Adj8</li>
     * </ul>
     */
    public void writeRelationships() {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                // Permission→Feature parent link
                .addUpdates(buildPreEncodedUpdate(
                        "frontegg_permission", "dGVzdC1wZXJtaXNzaW9uLTE",
                        "parent",
                        "frontegg_feature", "dGVzdC1mZWF0dXJlLTE"))
                // Feature→Plan parent link
                .addUpdates(buildPreEncodedUpdate(
                        "frontegg_feature", "dGVzdC1mZWF0dXJlLTE",
                        "parent",
                        "frontegg_plan", "ZjRkMzA3MTctZTY3MS00YjgzLWI3ODEtOWMzMTc3ZTZkNjkz"))
                // Plan entitled_tenant: test-tenant-1 with targeting caveat (empty rules, far-future expiry)
                .addUpdates(buildTargetingUpdate(
                        "frontegg_plan", "ZjRkMzA3MTctZTY3MS00YjgzLWI3ODEtOWMzMTc3ZTZkNjkz",
                        "entitled_tenant",
                        "frontegg_tenant", encode("test-tenant-1")))
                // cust FGA chain (IDs already encoded from YAML fixtures)
                .addUpdates(buildPreEncodedUpdate(
                        "cust_group", "Z3JvdXAtMS1SMU5pcFA0eQ",
                        "member",
                        "cust_user", "dXNlci0xLXI0dXAxYW9P"))
                .addUpdates(buildPreEncodedUpdate(
                        "cust_subscription", "c3ViLTEtOVhuMUFkajg",
                        "parent_group",
                        "cust_group", "Z3JvdXAtMS1SMU5pcFA0eQ"))
                .addUpdates(buildPreEncodedUpdate(
                        "cust_document", "ZG9jLTEtVlRqN1VEWjk",
                        "parent_subscription",
                        "cust_subscription", "c3ViLTEtOVhuMUFkajg"))
                .build();
        permissionsStub.writeRelationships(request);
    }

    /**
     * Writes caveat-based relationships for time-gated access tests using the {@code active_at} caveat.
     *
     * <p>Sets up:
     * <ul>
     *   <li>user-2-jan is a viewer of doc-2-jan (activeFrom 2026-01-01)</li>
     *   <li>user-2-jan is a viewer of doc-3-feb (activeFrom 2026-02-01)</li>
     *   <li>user-3-alice is a viewer of sub-2-jan (no caveat)</li>
     *   <li>doc-4-alice has parent_subscription sub-2-jan (activeFrom 2026-01-01)</li>
     * </ul>
     */
    public void writeCaveatRelationships() {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                // user-2-jan views doc-2-jan from 2026-01-01
                .addUpdates(buildCaveatUpdate(
                        "cust_document", encode("doc-2-jan"), "viewer",
                        "cust_user", encode("user-2-jan"),
                        "active_at", "2026-01-01T00:00:00.000Z", null))
                // user-2-jan views doc-3-feb from 2026-02-01
                .addUpdates(buildCaveatUpdate(
                        "cust_document", encode("doc-3-feb"), "viewer",
                        "cust_user", encode("user-2-jan"),
                        "active_at", "2026-02-01T00:00:00.000Z", null))
                // user-3-alice is a viewer of sub-2-jan (no caveat)
                .addUpdates(buildUpdate(
                        "cust_subscription", encode("sub-2-jan"), "viewer",
                        "cust_user", encode("user-3-alice")))
                // doc-4-alice has parent_subscription sub-2-jan from 2026-01-01
                .addUpdates(buildCaveatUpdate(
                        "cust_document", encode("doc-4-alice"), "parent_subscription",
                        "cust_subscription", encode("sub-2-jan"),
                        "active_at", "2026-01-01T00:00:00.000Z", null))
                .build();
        permissionsStub.writeRelationships(request);
    }

    /**
     * Builds a {@link RelationshipUpdate} using plain-text IDs that are Base64-encoded internally.
     */
    private static RelationshipUpdate buildUpdate(String resourceType, String resourceId,
                                                   String relation,
                                                   String subjectType, String subjectId) {
        return RelationshipUpdate.newBuilder()
                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                .setRelationship(Relationship.newBuilder()
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(resourceType)
                                .setObjectId(resourceId)
                                .build())
                        .setRelation(relation)
                        .setSubject(SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(subjectType)
                                        .setObjectId(subjectId)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Builds a {@link RelationshipUpdate} using already-encoded IDs (no additional encoding applied).
     * Use this when IDs come pre-encoded from the YAML fixture file.
     */
    private static RelationshipUpdate buildPreEncodedUpdate(String resourceType, String resourceId,
                                                              String relation,
                                                              String subjectType, String subjectId) {
        return RelationshipUpdate.newBuilder()
                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                .setRelationship(Relationship.newBuilder()
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(resourceType)
                                .setObjectId(resourceId)
                                .build())
                        .setRelation(relation)
                        .setSubject(SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(subjectType)
                                        .setObjectId(subjectId)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Builds a {@link RelationshipUpdate} with the {@code targeting} caveat using empty rules
     * and a far-future expiration ({@code 9000-01-02T00:00:00.000Z}).
     *
     * <p>The subject ID is expected to be already Base64-encoded. The resource ID is also
     * expected to be already Base64-encoded.
     *
     * <p>At check time the SDK supplies {@code user_context} (including {@code now}) automatically.
     */
    private static RelationshipUpdate buildTargetingUpdate(String resourceType, String resourceId,
                                                             String relation,
                                                             String subjectType, String subjectId) {
        Struct context = Struct.newBuilder()
                .putFields("expiration",
                        Value.newBuilder().setStringValue("9000-01-02T00:00:00.000Z").build())
                .putFields("rules",
                        Value.newBuilder().setListValue(ListValue.newBuilder().build()).build())
                .build();

        return RelationshipUpdate.newBuilder()
                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                .setRelationship(Relationship.newBuilder()
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(resourceType)
                                .setObjectId(resourceId)
                                .build())
                        .setRelation(relation)
                        .setSubject(SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(subjectType)
                                        .setObjectId(subjectId)
                                        .build())
                                .build())
                        .setOptionalCaveat(ContextualizedCaveat.newBuilder()
                                .setCaveatName("targeting")
                                .setContext(context)
                                .build())
                        .build())
                .build();
    }

    private static RelationshipUpdate buildCaveatUpdate(String resourceType, String resourceId,
                                                         String relation,
                                                         String subjectType, String subjectId,
                                                         String caveatName, String activeFrom,
                                                         String activeUntil) {
        Struct.Builder contextBuilder = Struct.newBuilder();
        if (activeFrom != null) {
            contextBuilder.putFields("activeFrom",
                    Value.newBuilder().setStringValue(activeFrom).build());
        } else {
            contextBuilder.putFields("activeFrom",
                    Value.newBuilder().setNullValueValue(0).build());
        }
        if (activeUntil != null) {
            contextBuilder.putFields("activeUntil",
                    Value.newBuilder().setStringValue(activeUntil).build());
        } else {
            contextBuilder.putFields("activeUntil",
                    Value.newBuilder().setNullValueValue(0).build());
        }

        return RelationshipUpdate.newBuilder()
                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                .setRelationship(Relationship.newBuilder()
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(resourceType)
                                .setObjectId(resourceId)
                                .build())
                        .setRelation(relation)
                        .setSubject(SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(subjectType)
                                        .setObjectId(subjectId)
                                        .build())
                                .build())
                        .setOptionalCaveat(ContextualizedCaveat.newBuilder()
                                .setCaveatName(caveatName)
                                .setContext(contextBuilder.build())
                                .build())
                        .build())
                .build();
    }

    /**
     * Writes the integration-test schema — a simpler schema used by {@code SpiceDBIntegrationTest}
     * that covers features, permissions, routes, and a {@code document/folder} FGA type.
     */
    public void writeIntegrationSchema() {
        String schema = """
                caveat active_at(at timestamp, activeFrom any, activeUntil any) {
                    (activeFrom == null || at >= timestamp(activeFrom)) && (activeUntil == null || at <= timestamp(activeUntil))
                }

                definition frontegg_user {}

                definition frontegg_tenant {}

                definition frontegg_feature {
                    relation entitled: frontegg_user | frontegg_tenant
                    permission access = entitled
                }

                definition frontegg_permission {
                    relation entitled: frontegg_user | frontegg_tenant
                    relation parent: frontegg_feature
                    permission access = entitled
                }

                caveat route_policy(pattern string, policy_type string, priority double, monitoring bool) {
                    pattern != "" && policy_type != "" && priority >= 0.0 && (monitoring || !monitoring)
                }

                definition frontegg_route {
                    relation entitled: frontegg_user with route_policy | frontegg_tenant with route_policy
                    permission access = entitled
                }

                definition document {
                    relation viewer: frontegg_user
                    relation editor: frontegg_user
                    relation parent: folder | folder with active_at
                    relation reader: frontegg_user | frontegg_user with active_at
                    permission view = viewer + editor
                    permission edit = editor
                    permission read_doc = parent->read_folder + reader
                }

                definition folder {
                    relation reader: frontegg_user | frontegg_user with active_at
                    permission read_folder = reader
                }
                """;

        schemaStub.writeSchema(WriteSchemaRequest.newBuilder()
                .setSchema(schema)
                .build());
    }

    /**
     * Writes fixture relationships for the integration test schema.
     *
     * <p>Fixture data:
     * <ul>
     *   <li>user-1 in tenant-1 entitled to feature "premium" (both user and tenant)</li>
     *   <li>user-1 in tenant-1 entitled to permission "reports:read" (both user and tenant)</li>
     *   <li>user-1 entitled to route "GET:/api/v1/reports" (user-level only)</li>
     *   <li>user-1 is viewer of document "doc-1", editor of "doc-2" (FGA)</li>
     * </ul>
     */
    public void writeIntegrationRelationships() {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                .addUpdates(buildUpdate("frontegg_feature", encode("premium"), "entitled",
                        "frontegg_user", encode("user-1")))
                .addUpdates(buildUpdate("frontegg_feature", encode("premium"), "entitled",
                        "frontegg_tenant", encode("tenant-1")))
                .addUpdates(buildUpdate("frontegg_permission", encode("reports:read"), "entitled",
                        "frontegg_user", encode("user-1")))
                .addUpdates(buildUpdate("frontegg_permission", encode("reports:read"), "entitled",
                        "frontegg_tenant", encode("tenant-1")))
                .addUpdates(buildRouteUpdate("frontegg_route", encode("GET:/api/v1/reports"), "entitled",
                        "frontegg_user", encode("user-1"),
                        "GET /api/v1/reports", "allow", 0.0, false))
                .addUpdates(buildUpdate("document", encode("doc-1"), "viewer",
                        "frontegg_user", encode("user-1")))
                .addUpdates(buildUpdate("document", encode("doc-2"), "editor",
                        "frontegg_user", encode("user-1")))
                .build();
        permissionsStub.writeRelationships(request);
    }

    /**
     * Writes caveat-based relationships for the integration test schema (Tim/Alice salary demo).
     */
    public void writeIntegrationCaveatRelationships() {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                .addUpdates(buildUpdate("folder", encode("salaries"), "reader",
                        "frontegg_user", encode("Alice")))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Jan"), "reader",
                        "frontegg_user", encode("Tim"),
                        "active_at", "2026-01-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Feb"), "reader",
                        "frontegg_user", encode("Tim"),
                        "active_at", "2026-02-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Mar"), "reader",
                        "frontegg_user", encode("Tim"),
                        "active_at", "2026-03-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Jan"), "parent",
                        "folder", encode("salaries"),
                        "active_at", "2026-01-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Feb"), "parent",
                        "folder", encode("salaries"),
                        "active_at", "2026-02-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Mar"), "parent",
                        "folder", encode("salaries"),
                        "active_at", "2026-03-01T00:00:00.000Z", null))
                .build();
        permissionsStub.writeRelationships(request);
    }

    private static RelationshipUpdate buildRouteUpdate(String resourceType, String resourceId,
                                                        String relation,
                                                        String subjectType, String subjectId,
                                                        String pattern, String policyType,
                                                        double priority, boolean monitoring) {
        Struct context = Struct.newBuilder()
                .putFields("pattern", Value.newBuilder().setStringValue(pattern).build())
                .putFields("policy_type", Value.newBuilder().setStringValue(policyType).build())
                .putFields("priority", Value.newBuilder().setNumberValue(priority).build())
                .putFields("monitoring", Value.newBuilder().setBoolValue(monitoring).build())
                .build();
        return RelationshipUpdate.newBuilder()
                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                .setRelationship(Relationship.newBuilder()
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(resourceType)
                                .setObjectId(resourceId)
                                .build())
                        .setRelation(relation)
                        .setSubject(SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(subjectType)
                                        .setObjectId(subjectId)
                                        .build())
                                .build())
                        .setOptionalCaveat(ContextualizedCaveat.newBuilder()
                                .setCaveatName("route_policy")
                                .setContext(context)
                                .build())
                        .build())
                .build();
    }

    /**
     * Encodes a plain-text ID to URL-safe Base64 without padding, matching
     * {@code Base64Utils.encode()} in the SDK's internal package.
     */
    static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Shuts down the underlying gRPC channel. Call in {@code @AfterAll}.
     */
    public void close() {
        channel.shutdownNow();
    }
}
