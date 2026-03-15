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
     * Writes the SpiceDB schema covering all resource types used by the integration tests:
     * frontegg_user, frontegg_tenant, frontegg_feature, frontegg_permission, frontegg_route,
     * and a generic {@code document} type for FGA checks.
     */
    public void writeSchema() {
        String schema = """
                caveat active_at(at timestamp, activeFrom any, activeUntil any) {
                    (activeFrom == null || at >= timestamp(activeFrom)) && (activeUntil == null || at <= timestamp(activeUntil))
                }

                definition frontegg_user {}

                definition frontegg_tenant {}

                definition frontegg_feature {
                    relation entitled: frontegg_user | frontegg_tenant
                }

                definition frontegg_permission {
                    relation entitled: frontegg_user | frontegg_tenant
                }

                definition frontegg_route {
                    relation entitled: frontegg_user | frontegg_tenant
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

        var schemaResponse = schemaStub.writeSchema(WriteSchemaRequest.newBuilder()
                .setSchema(schema)
                .build());
        System.out.println("=== DEBUG: writeSchema response writtenAt=" + schemaResponse.getWrittenAt() + " ===");
    }

    /**
     * Writes all fixture relationships used by the integration tests in a single batch request.
     *
     * <p>Using a single {@code WriteRelationships} call ensures that all relationships share
     * the same ZedToken (revision), so the returned ZedToken represents a snapshot that
     * contains all of them atomically.
     *
     * <p>Fixture data:
     * <ul>
     *   <li>user-1 in tenant-1 entitled to feature "premium" (both user and tenant relationships)</li>
     *   <li>user-1 in tenant-1 entitled to permission "reports:read" (both user and tenant)</li>
     *   <li>user-1 entitled to route "GET:/api/v1/reports" (user-level only)</li>
     *   <li>user-1 is viewer of document "doc-1" (FGA)</li>
     *   <li>user-1 is editor of document "doc-2" (FGA — editor implies view via schema)</li>
     * </ul>
     *
     * <p>Notably absent: no relationship for permission "admin:write" — used to test denied checks.
     */
    public void writeRelationships() {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                // Feature "premium": user-1 and tenant-1 both entitled
                .addUpdates(buildUpdate("frontegg_feature", encode("premium"), "entitled",
                        "frontegg_user", encode("user-1")))
                .addUpdates(buildUpdate("frontegg_feature", encode("premium"), "entitled",
                        "frontegg_tenant", encode("tenant-1")))
                // Permission "reports:read": user-1 and tenant-1 both entitled
                .addUpdates(buildUpdate("frontegg_permission", encode("reports:read"), "entitled",
                        "frontegg_user", encode("user-1")))
                .addUpdates(buildUpdate("frontegg_permission", encode("reports:read"), "entitled",
                        "frontegg_tenant", encode("tenant-1")))
                // Route "GET:/api/v1/reports": user-1 entitled (user-level only)
                // RouteSpiceDBQuery formats the key as METHOD:PATH before encoding
                .addUpdates(buildUpdate("frontegg_route", encode("GET:/api/v1/reports"), "entitled",
                        "frontegg_user", encode("user-1")))
                // FGA: user-1 is viewer of doc-1, editor of doc-2
                .addUpdates(buildUpdate("document", encode("doc-1"), "viewer",
                        "frontegg_user", encode("user-1")))
                .addUpdates(buildUpdate("document", encode("doc-2"), "editor",
                        "frontegg_user", encode("user-1")))
                .build();
        var response = permissionsStub.writeRelationships(request);
        System.out.println("=== DEBUG: writeRelationships response writtenAt=" + response.getWrittenAt() + " ===");
    }

    /**
     * Writes caveat-based relationships for time-gated access tests.
     *
     * <p>Ported from the Node.js SDK demo. Sets up:
     * <ul>
     *   <li>Alice is a reader of the "salaries" folder (no caveat — always allowed)</li>
     *   <li>Tim is a direct reader of salary docs (with active_at caveat per month)</li>
     *   <li>Salary docs have "salaries" folder as parent (with active_at caveat per month)</li>
     * </ul>
     *
     * <p>This enables testing:
     * <ul>
     *   <li>Time-based direct access (Tim can read doc at/after activeFrom)</li>
     *   <li>Permission inheritance through folder (Alice can read docs via folder reader)</li>
     *   <li>Time-filtered lookups (lookupResources/lookupSubjects with at parameter)</li>
     * </ul>
     */
    public void writeCaveatRelationships() {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                // Alice is always a reader of the salaries folder (no caveat)
                .addUpdates(buildUpdate("folder", encode("salaries"), "reader",
                        "frontegg_user", encode("Alice")))
                // Tim is a direct reader of salary docs with time-based caveats
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Jan"), "reader",
                        "frontegg_user", encode("Tim"),
                        "active_at", "2026-01-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Feb"), "reader",
                        "frontegg_user", encode("Tim"),
                        "active_at", "2026-02-01T00:00:00.000Z", null))
                .addUpdates(buildCaveatUpdate("document", encode("Tim's_salary_Mar"), "reader",
                        "frontegg_user", encode("Tim"),
                        "active_at", "2026-03-01T00:00:00.000Z", null))
                // Salary docs have "salaries" folder as parent (with time-based caveats)
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
        var caveatResponse = permissionsStub.writeRelationships(request);
        System.out.println("=== DEBUG: writeCaveatRelationships response writtenAt=" + caveatResponse.getWrittenAt() + " ===");
    }

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

    private static RelationshipUpdate buildCaveatUpdate(String resourceType, String resourceId,
                                                         String relation,
                                                         String subjectType, String subjectId,
                                                         String caveatName, String activeFrom,
                                                         String activeUntil) {
        Struct.Builder contextBuilder = Struct.newBuilder();
        if (activeFrom != null) {
            contextBuilder.putFields("activeFrom",
                    Value.newBuilder().setStringValue(activeFrom).build());
        }
        if (activeUntil != null) {
            contextBuilder.putFields("activeUntil",
                    Value.newBuilder().setStringValue(activeUntil).build());
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
     * Encodes a plain-text ID to URL-safe Base64 without padding, matching
     * {@code Base64Utils.encode()} in the SDK's internal package.
     */
    static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Performs a direct CheckPermission call for diagnostics, bypassing the SDK.
     */
    public String directCheckPermission(String subjectType, String subjectId,
                                          String resourceType, String resourceId,
                                          String permission, String atTimestamp) {
        com.authzed.api.v1.CheckPermissionRequest.Builder reqBuilder =
                com.authzed.api.v1.CheckPermissionRequest.newBuilder()
                        .setConsistency(com.authzed.api.v1.Consistency.newBuilder()
                                .setFullyConsistent(true)
                                .build())
                        .setSubject(com.authzed.api.v1.SubjectReference.newBuilder()
                                .setObject(ObjectReference.newBuilder()
                                        .setObjectType(subjectType)
                                        .setObjectId(encode(subjectId))
                                        .build())
                                .build())
                        .setResource(ObjectReference.newBuilder()
                                .setObjectType(resourceType)
                                .setObjectId(encode(resourceId))
                                .build())
                        .setPermission(permission);

        if (atTimestamp != null) {
            reqBuilder.setContext(com.google.protobuf.Struct.newBuilder()
                    .putFields("at", com.google.protobuf.Value.newBuilder()
                            .setStringValue(atTimestamp)
                            .build())
                    .build());
        }

        var resp = permissionsStub.checkPermission(reqBuilder.build());
        return resp.getPermissionship().name();
    }

    /**
     * Reads back the current schema from SpiceDB (for diagnostics).
     *
     * @return the schema text currently stored in SpiceDB
     */
    public String readSchema() {
        var response = schemaStub.readSchema(
                com.authzed.api.v1.ReadSchemaRequest.newBuilder().build());
        return response.getSchemaText();
    }

    /**
     * Reads back all relationships for a given resource type (for diagnostics).
     *
     * @return list of relationship strings
     */
    public java.util.List<String> readRelationships(String resourceType) {
        var response = permissionsStub.readRelationships(
                com.authzed.api.v1.ReadRelationshipsRequest.newBuilder()
                        .setConsistency(com.authzed.api.v1.Consistency.newBuilder()
                                .setFullyConsistent(true)
                                .build())
                        .setRelationshipFilter(com.authzed.api.v1.RelationshipFilter.newBuilder()
                                .setResourceType(resourceType)
                                .build())
                        .build());
        java.util.List<String> results = new java.util.ArrayList<>();
        while (response.hasNext()) {
            var r = response.next().getRelationship();
            results.add(r.getResource().getObjectType() + ":" + r.getResource().getObjectId()
                    + "#" + r.getRelation()
                    + "@" + r.getSubject().getObject().getObjectType() + ":" + r.getSubject().getObject().getObjectId()
                    + (r.hasOptionalCaveat() && !r.getOptionalCaveat().getCaveatName().isEmpty()
                            ? "[" + r.getOptionalCaveat().getCaveatName() + "]" : ""));
        }
        return results;
    }

    /**
     * Shuts down the underlying gRPC channel. Call in {@code @AfterAll}.
     */
    public void close() {
        channel.shutdownNow();
    }
}
