package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsResponse;
import com.authzed.api.v1.CheckPermissionResponse;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SpiceDBQueryClient}.
 *
 * <p>Uses the package-private test constructor that accepts pre-built query strategies
 * built with {@link BulkPermissionsExecutor} and {@link CheckPermissionExecutor} lambdas,
 * avoiding the need to create a real gRPC blocking stub (which is a {@code final} class
 * that cannot be mocked on JDK 25).
 */
class SpiceDBQueryClientTest {

    private final AtomicBoolean featureQueryInvoked = new AtomicBoolean(false);
    private final AtomicBoolean permissionQueryInvoked = new AtomicBoolean(false);
    private final AtomicBoolean fgaQueryInvoked = new AtomicBoolean(false);
    private final AtomicBoolean routeQueryInvoked = new AtomicBoolean(false);
    private SpiceDBQueryClient queryClient;
    private UserSubjectContext userCtx;
    private EntitySubjectContext entityCtx;

    @BeforeEach
    void setUp() {
        java.util.function.Supplier<com.authzed.api.v1.Consistency> consistency =
                () -> com.authzed.api.v1.Consistency.newBuilder().setMinimizeLatency(true).build();

        FeatureSpiceDBQuery capturedFeatureQuery = new FeatureSpiceDBQuery(req -> {
            featureQueryInvoked.set(true);
            return CheckBulkPermissionsResponse.newBuilder().build();
        }, consistency);

        PermissionSpiceDBQuery capturedPermissionQuery = new PermissionSpiceDBQuery(req -> {
            permissionQueryInvoked.set(true);
            return CheckBulkPermissionsResponse.newBuilder().build();
        }, req -> java.util.List.of(com.authzed.api.v1.LookupSubjectsResponse.newBuilder().build()).iterator(),
                consistency);

        FgaSpiceDBQuery capturedFgaQuery = new FgaSpiceDBQuery(req -> {
            fgaQueryInvoked.set(true);
            return CheckPermissionResponse.newBuilder()
                    .setPermissionship(
                            CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)
                    .build();
        }, consistency);

        // ReadRelationships returns a ruleBased rule matching any route so the bulk executor is reached
        RouteSpiceDBQuery capturedRouteQuery = new RouteSpiceDBQuery(
                req -> List.of(RouteSpiceDBQueryTest.ruleBasedRelationship(".*", 0)),
                req -> {
                    routeQueryInvoked.set(true);
                    return CheckBulkPermissionsResponse.newBuilder().build();
                },
                consistency);

        queryClient = new SpiceDBQueryClient(capturedFeatureQuery, capturedPermissionQuery,
                capturedFgaQuery, capturedRouteQuery);
        userCtx = new UserSubjectContext("user-1", "tenant-1", List.of("read:data"), Map.of());
        entityCtx = new EntitySubjectContext("service_account", "svc-deployer-01");
    }

    // -------------------------------------------------------------------------
    // Dispatch to feature query
    // -------------------------------------------------------------------------

    @Test
    void execute_featureRequestWithUserSubject_dispatchesToFeatureQuery() {
        queryClient.execute(userCtx, new FeatureRequestContext("my-feature"));

        assertTrue(featureQueryInvoked.get(),
                "FeatureSpiceDBQuery executor must have been called");
    }

    // -------------------------------------------------------------------------
    // Dispatch to permission query
    // -------------------------------------------------------------------------

    @Test
    void execute_permissionRequestContext_dispatchesToPermissionQuery() {
        EntitlementsResult result = assertDoesNotThrow(
                () -> queryClient.execute(userCtx, new PermissionRequestContext("read:data")),
                "PermissionRequestContext must not throw UnsupportedOperationException");

        assertTrue(permissionQueryInvoked.get(),
                "PermissionSpiceDBQuery executor must have been called");
        assertNotNull(result, "result must not be null");
    }

    // -------------------------------------------------------------------------
    // Dispatch to FGA query
    // -------------------------------------------------------------------------

    @Test
    void execute_entityRequestWithEntitySubject_dispatchesToFgaQuery() {
        EntitlementsResult result = assertDoesNotThrow(
                () -> queryClient.execute(
                        entityCtx,
                        new EntityRequestContext("document", "doc-123", "viewer")),
                "EntityRequestContext with EntitySubjectContext must not throw");

        assertTrue(fgaQueryInvoked.get(),
                "FgaSpiceDBQuery executor must have been called");
        assertNotNull(result, "result must not be null");
    }

    @Test
    void execute_entityRequestWithEntitySubject_returnsExpectedResult() {
        EntitlementsResult result = queryClient.execute(
                entityCtx,
                new EntityRequestContext("document", "doc-123", "viewer"));

        assertTrue(result.result(), "FGA allowed → result must be true");
    }

    // -------------------------------------------------------------------------
    // Wrong subject type for EntityRequestContext
    // -------------------------------------------------------------------------

    @Test
    void execute_entityRequestWithUserSubject_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class,
                () -> queryClient.execute(
                        userCtx,
                        new EntityRequestContext("document", "doc-123", "viewer")));
    }

    // -------------------------------------------------------------------------
    // Dispatch to route query
    // -------------------------------------------------------------------------

    @Test
    void execute_routeRequestWithUserSubject_dispatchesToRouteQuery() {
        EntitlementsResult result = assertDoesNotThrow(
                () -> queryClient.execute(userCtx, new RouteRequestContext("GET", "/api/v1/resources")),
                "RouteRequestContext with UserSubjectContext must not throw");

        assertTrue(routeQueryInvoked.get(),
                "RouteSpiceDBQuery executor must have been called");
        assertNotNull(result, "result must not be null");
    }

    @Test
    void execute_routeRequestWithEntitySubject_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class,
                () -> queryClient.execute(
                        entityCtx,
                        new RouteRequestContext("GET", "/api/v1/resources")));
    }

    // -------------------------------------------------------------------------
    // Unsupported combinations
    // -------------------------------------------------------------------------

    @Test
    void execute_permissionRequestWithEntitySubject_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class,
                () -> queryClient.execute(
                        entityCtx,
                        new PermissionRequestContext("read:data")));
    }
}
