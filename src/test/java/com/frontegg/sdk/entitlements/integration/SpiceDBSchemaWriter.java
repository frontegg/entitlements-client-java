package com.frontegg.sdk.entitlements.integration;

import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.PermissionsServiceGrpc;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.RelationshipUpdate;
import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.SubjectReference;
import com.authzed.api.v1.WriteRelationshipsRequest;
import com.authzed.api.v1.WriteSchemaRequest;
import com.authzed.grpcutil.BearerToken;
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
                    permission view = viewer + editor
                    permission edit = editor
                }
                """;

        schemaStub.writeSchema(WriteSchemaRequest.newBuilder()
                .setSchema(schema)
                .build());
    }

    /**
     * Writes all fixture relationships used by the integration tests.
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
        // Feature "premium": user-1 and tenant-1 both entitled
        writeRelationship("frontegg_feature", encode("premium"), "entitled",
                "frontegg_user", encode("user-1"));
        writeRelationship("frontegg_feature", encode("premium"), "entitled",
                "frontegg_tenant", encode("tenant-1"));

        // Permission "reports:read": user-1 and tenant-1 both entitled
        writeRelationship("frontegg_permission", encode("reports:read"), "entitled",
                "frontegg_user", encode("user-1"));
        writeRelationship("frontegg_permission", encode("reports:read"), "entitled",
                "frontegg_tenant", encode("tenant-1"));

        // Route "GET:/api/v1/reports": user-1 entitled (user-level only)
        // RouteSpiceDBQuery formats the key as METHOD:PATH before encoding
        writeRelationship("frontegg_route", encode("GET:/api/v1/reports"), "entitled",
                "frontegg_user", encode("user-1"));

        // FGA: user-1 is viewer of doc-1, editor of doc-2
        writeRelationship("document", encode("doc-1"), "viewer",
                "frontegg_user", encode("user-1"));
        writeRelationship("document", encode("doc-2"), "editor",
                "frontegg_user", encode("user-1"));
    }

    private void writeRelationship(String resourceType, String resourceId,
                                   String relation, String subjectType, String subjectId) {
        WriteRelationshipsRequest request = WriteRelationshipsRequest.newBuilder()
                .addUpdates(RelationshipUpdate.newBuilder()
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
                        .build())
                .build();
        permissionsStub.writeRelationships(request);
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
