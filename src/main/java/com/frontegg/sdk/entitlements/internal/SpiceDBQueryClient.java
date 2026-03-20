package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.PermissionsServiceGrpc;
import com.authzed.api.v1.ReadRelationshipsResponse;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.EntityRequestContext;
import com.frontegg.sdk.entitlements.model.EntitySubjectContext;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.PermissionRequestContext;
import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.RouteRequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import io.grpc.Deadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Package-private strategy dispatcher that routes an entitlement check to the correct
 * query strategy based on the {@link RequestContext} type.
 *
 * <p>This is the single point that maps the public sealed-interface hierarchy to internal query
 * strategies, keeping all dispatch logic in one place.
 *
 * <p>Each query strategy receives a {@link BulkPermissionsExecutor},
 * {@link CheckPermissionExecutor}, {@link LookupResourcesExecutor}, or
 * {@link LookupSubjectsExecutor} functional interface rather than the raw gRPC stub,
 * which keeps strategy classes testable without requiring Mockito to instrument final
 * gRPC classes.
 */
class SpiceDBQueryClient {

    private static final Logger log = LoggerFactory.getLogger(SpiceDBQueryClient.class);

    private final FeatureSpiceDBQuery featureQuery;
    private final PermissionSpiceDBQuery permissionQuery;
    private final FgaSpiceDBQuery fgaQuery;
    private final RouteSpiceDBQuery routeQuery;
    private final LookupSpiceDBQuery lookupQuery;

    SpiceDBQueryClient(PermissionsServiceGrpc.PermissionsServiceBlockingStub stub,
                       ClientConfiguration config) {
        // Build the bulk executor lambda once: applies the bulk deadline and delegates to the stub.
        BulkPermissionsExecutor bulkExecutor = request -> stub
                .withDeadline(Deadline.after(
                        config.getBulkRequestTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .checkBulkPermissions(request);

        // Build the single-check executor lambda: applies the (non-bulk) request deadline.
        CheckPermissionExecutor checkExecutor = request -> stub
                .withDeadline(Deadline.after(
                        config.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .checkPermission(request);

        // Build lookup executor lambdas: apply the bulk deadline for streaming RPCs.
        LookupResourcesExecutor lookupResourcesExec = request -> stub
                .withDeadline(Deadline.after(
                        config.getBulkRequestTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .lookupResources(request);

        LookupSubjectsExecutor lookupSubjectsExec = request -> stub
                .withDeadline(Deadline.after(
                        config.getBulkRequestTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .lookupSubjects(request);

        ReadRelationshipsExecutor readRelationshipsExec = request -> {
            Iterator<ReadRelationshipsResponse> it = stub
                    .withDeadline(Deadline.after(
                            config.getBulkRequestTimeout().toMillis(), TimeUnit.MILLISECONDS))
                    .readRelationships(request);
            List<ReadRelationshipsResponse> results = new ArrayList<>();
            while (it.hasNext()) results.add(it.next());
            return results;
        };

        Supplier<Consistency> consistency = ConsistencyFactory.supplierFor(config.getConsistencyPolicy());

        this.featureQuery = new FeatureSpiceDBQuery(bulkExecutor, consistency);
        this.permissionQuery = new PermissionSpiceDBQuery(bulkExecutor, lookupSubjectsExec, consistency);
        this.fgaQuery = new FgaSpiceDBQuery(checkExecutor, consistency);
        this.routeQuery = new RouteSpiceDBQuery(readRelationshipsExec, bulkExecutor, consistency);
        this.lookupQuery = new LookupSpiceDBQuery(lookupResourcesExec, lookupSubjectsExec, consistency);
    }

    /**
     * Package-private test constructor that accepts pre-built query strategies.
     * Allows unit tests to inject controlled query strategy instances directly,
     * avoiding the need to instantiate a real gRPC blocking stub.
     */
    SpiceDBQueryClient(FeatureSpiceDBQuery featureQuery,
                       PermissionSpiceDBQuery permissionQuery,
                       FgaSpiceDBQuery fgaQuery,
                       RouteSpiceDBQuery routeQuery) {
        this.featureQuery = featureQuery;
        this.permissionQuery = permissionQuery;
        this.fgaQuery = fgaQuery;
        this.routeQuery = routeQuery;
        this.lookupQuery = new LookupSpiceDBQuery(
                req -> { throw new UnsupportedOperationException("lookup not wired in test constructor"); },
                req -> { throw new UnsupportedOperationException("lookup not wired in test constructor"); },
                ConsistencyFactory.supplierFor(com.frontegg.sdk.entitlements.config.ConsistencyPolicy.MINIMIZE_LATENCY)
        );
    }

    /**
     * Package-private test constructor that accepts pre-built query strategies including lookup.
     * Allows unit tests to inject controlled query strategy instances directly,
     * avoiding the need to instantiate a real gRPC blocking stub.
     */
    SpiceDBQueryClient(FeatureSpiceDBQuery featureQuery,
                       PermissionSpiceDBQuery permissionQuery,
                       FgaSpiceDBQuery fgaQuery,
                       RouteSpiceDBQuery routeQuery,
                       LookupSpiceDBQuery lookupQuery) {
        this.featureQuery = featureQuery;
        this.permissionQuery = permissionQuery;
        this.fgaQuery = fgaQuery;
        this.routeQuery = routeQuery;
        this.lookupQuery = lookupQuery;
    }

    /**
     * Executes the entitlement check by dispatching to the appropriate query strategy.
     *
     * @param subject the subject being checked; must not be {@code null}
     * @param request the resource or permission being checked; must not be {@code null}
     * @return the entitlement result
     * @throws UnsupportedOperationException if the combination of subject/request is not yet
     *                                       implemented
     * @throws IllegalArgumentException      if the request context type is unrecognised
     */
    EntitlementsResult execute(SubjectContext subject, RequestContext request) {
        log.debug("Dispatching entitlement check subjectType={} requestType={}",
                subject.getClass().getSimpleName(), request.getClass().getSimpleName());

        if (request instanceof FeatureRequestContext feat) {
            if (subject instanceof UserSubjectContext user) {
                return featureQuery.query(user, feat);
            }
            throw new UnsupportedOperationException(
                    "FeatureRequestContext requires a UserSubjectContext; got: "
                            + subject.getClass().getSimpleName());
        }
        if (request instanceof PermissionRequestContext perm) {
            if (subject instanceof UserSubjectContext user) {
                return permissionQuery.query(user, perm);
            }
            throw new UnsupportedOperationException(
                    "PermissionRequestContext requires a UserSubjectContext; got: "
                            + subject.getClass().getSimpleName());
        }
        if (request instanceof EntityRequestContext entity) {
            if (subject instanceof EntitySubjectContext entitySubject) {
                return fgaQuery.query(entitySubject, entity);
            }
            throw new UnsupportedOperationException(
                    "EntityRequestContext requires an EntitySubjectContext; got: "
                            + subject.getClass().getSimpleName());
        }
        if (request instanceof RouteRequestContext route) {
            if (subject instanceof UserSubjectContext user) {
                return routeQuery.query(user, route);
            }
            throw new UnsupportedOperationException(
                    "RouteRequestContext requires a UserSubjectContext; got: "
                            + subject.getClass().getSimpleName());
        }
        throw new IllegalArgumentException(
                "Unsupported request context type: " + request.getClass().getName());
    }

    /**
     * Delegates to {@link LookupSpiceDBQuery#lookupResources}.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link LookupResult} containing matching resource IDs
     */
    LookupResult lookupResources(LookupResourcesRequest request) {
        return lookupQuery.lookupResources(request);
    }

    /**
     * Delegates to {@link LookupSpiceDBQuery#lookupSubjects}.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link LookupResult} containing matching subject IDs
     */
    LookupResult lookupSubjects(LookupSubjectsRequest request) {
        return lookupQuery.lookupSubjects(request);
    }
}
