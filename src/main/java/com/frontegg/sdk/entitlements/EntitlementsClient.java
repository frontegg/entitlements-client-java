package com.frontegg.sdk.entitlements;

import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.exception.EntitlementsTimeoutException;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * Main entry point for performing entitlement checks against the Frontegg authorization engine.
 *
 * <p>An {@code EntitlementsClient} is thread-safe and intended to be shared across the lifetime
 * of an application. A single instance manages an underlying gRPC channel with HTTP/2
 * multiplexing; you should create one instance and reuse it rather than creating a new client
 * per request.
 *
 * <p>Obtain an instance via {@link EntitlementsClientFactory}:
 *
 * <pre>{@code
 * ClientConfiguration config = ClientConfiguration.builder()
 *         .engineEndpoint("grpc.authz.example.com:443")
 *         .engineToken(System.getenv("ENTITLEMENTS_ENGINE_TOKEN"))
 *         .build();
 *
 * try (EntitlementsClient client = EntitlementsClientFactory.create(config)) {
 *     EntitlementsResult result = client.isEntitledTo(
 *             new UserSubjectContext("user-123", "tenant-456"),
 *             new FeatureRequestContext("feature-flag-key")
 *     );
 *     if (result.result()) {
 *         // grant access
 *     }
 * }
 * }</pre>
 *
 * @see EntitlementsClientFactory
 * @see com.frontegg.sdk.entitlements.config.ClientConfiguration
 * @since 0.1.0
 */
public interface EntitlementsClient extends Closeable {

    /**
     * Synchronously checks whether the given subject is entitled to the given resource or
     * permission.
     *
     * <p>This method blocks until the authorization engine responds or the configured
     * {@code requestTimeout} / {@code bulkRequestTimeout} is exceeded. Transient failures are
     * retried up to {@code maxRetries} times with exponential backoff. If a
     * {@link com.frontegg.sdk.entitlements.fallback.FallbackStrategy} is configured it is invoked
     * after all retries are exhausted; otherwise the exception propagates to the caller.
     *
     * @param subjectContext identifies who is being checked (user or entity)
     * @param requestContext identifies what is being checked (feature, permission, route, or FGA)
     * @return an {@link EntitlementsResult} whose {@code result} field is {@code true} when the
     *         subject is entitled and {@code false} when it is not
     * @throws EntitlementsQueryException  if the authorization engine returns an error and no
     *                                     fallback strategy is configured
     * @throws EntitlementsTimeoutException if the gRPC deadline is exceeded and no fallback
     *                                      strategy is configured
     * @throws IllegalStateException        if the client has already been closed
     * @since 0.1.0
     */
    EntitlementsResult isEntitledTo(SubjectContext subjectContext, RequestContext requestContext);

    /**
     * Asynchronously checks whether the given subject is entitled to the given resource or
     * permission.
     *
     * <p>The returned {@link CompletableFuture} completes on a gRPC callback thread. Callers
     * must not perform blocking operations in chained stages without switching to an appropriate
     * executor. The same retry and fallback behaviour as {@link #isEntitledTo} applies.
     *
     * @param subjectContext identifies who is being checked (user or entity)
     * @param requestContext identifies what is being checked (feature, permission, route, or FGA)
     * @return a {@link CompletableFuture} that resolves to an {@link EntitlementsResult};
     *         completed exceptionally with {@link EntitlementsQueryException} or
     *         {@link EntitlementsTimeoutException} on failure when no fallback is configured
     * @throws IllegalStateException if the client has already been closed
     * @since 0.1.0
     */
    CompletableFuture<EntitlementsResult> isEntitledToAsync(SubjectContext subjectContext,
                                                             RequestContext requestContext);

    /**
     * Looks up all resources of a given type that the subject has access to.
     *
     * <p>Maps to the SpiceDB {@code LookupResources} RPC. Blocks until the authorization engine
     * responds or the configured {@code requestTimeout} is exceeded. Transient failures are
     * retried up to {@code maxRetries} times with exponential backoff.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link LookupResult} containing matching resource IDs; never {@code null}
     * @throws EntitlementsQueryException   if the authorization engine returns an error
     * @throws EntitlementsTimeoutException if the gRPC deadline is exceeded
     * @throws IllegalStateException        if the client has already been closed
     * @since 0.2.0
     */
    LookupResult lookupResources(LookupResourcesRequest request);

    /**
     * Asynchronously looks up all resources of a given type that the subject has access to.
     *
     * <p>The returned {@link CompletableFuture} completes on a gRPC callback thread. The same
     * retry behaviour as {@link #lookupResources} applies.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link CompletableFuture} that resolves to a {@link LookupResult}
     * @throws IllegalStateException if the client has already been closed
     * @since 0.2.0
     */
    CompletableFuture<LookupResult> lookupResourcesAsync(LookupResourcesRequest request);

    /**
     * Looks up all subjects of a given type that have access to a resource.
     *
     * <p>Maps to the SpiceDB {@code LookupSubjects} RPC. Blocks until the authorization engine
     * responds or the configured {@code requestTimeout} is exceeded. Transient failures are
     * retried up to {@code maxRetries} times with exponential backoff.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link LookupResult} containing matching subject IDs; never {@code null}
     * @throws EntitlementsQueryException   if the authorization engine returns an error
     * @throws EntitlementsTimeoutException if the gRPC deadline is exceeded
     * @throws IllegalStateException        if the client has already been closed
     * @since 0.2.0
     */
    LookupResult lookupSubjects(LookupSubjectsRequest request);

    /**
     * Asynchronously looks up all subjects of a given type that have access to a resource.
     *
     * <p>The returned {@link CompletableFuture} completes on a gRPC callback thread. The same
     * retry behaviour as {@link #lookupSubjects} applies.
     *
     * @param request the lookup request; must not be {@code null}
     * @return a {@link CompletableFuture} that resolves to a {@link LookupResult}
     * @throws IllegalStateException if the client has already been closed
     * @since 0.2.0
     */
    CompletableFuture<LookupResult> lookupSubjectsAsync(LookupSubjectsRequest request);

    /**
     * Shuts down the underlying gRPC channel and releases all associated resources.
     *
     * <p>After this method returns the client must not be used. Any in-flight requests may be
     * cancelled. Calling {@code close()} more than once is safe and has no additional effect.
     *
     * @since 0.1.0
     */
    @Override
    void close();
}
