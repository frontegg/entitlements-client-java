package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.PermissionsServiceGrpc;
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.cache.CacheProvider;
import com.frontegg.sdk.entitlements.cache.CaffeineCacheProvider;
import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.exception.EntitlementsTimeoutException;
import com.frontegg.sdk.entitlements.fallback.FallbackContext;
import com.frontegg.sdk.entitlements.fallback.FallbackStrategy;
import com.frontegg.sdk.entitlements.fallback.FunctionFallback;
import com.frontegg.sdk.entitlements.fallback.StaticFallback;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal implementation of {@link EntitlementsClient} backed by a SpiceDB gRPC channel.
 *
 * <p>This class is package-private and must only be instantiated by
 * {@link com.frontegg.sdk.entitlements.EntitlementsClientFactory}. Consumers interact
 * exclusively through the {@link EntitlementsClient} interface.
 *
 * <p>Thread-safe: the {@link AtomicBoolean} closed flag ensures that {@link #close()} is
 * idempotent and that use-after-close is detected correctly across threads.
 */
class SpiceDBEntitlementsClient implements EntitlementsClient {

    private static final Logger log = LoggerFactory.getLogger(SpiceDBEntitlementsClient.class);

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ManagedChannel channel;
    private final ClientConfiguration config;
    private final SpiceDBQueryClient queryClient;
    private final RetryHandler retryHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Nullable — {@code null} means caching is disabled. */
    private final CacheProvider<EntitlementsCacheKey, EntitlementsResult> cache;

    SpiceDBEntitlementsClient(ManagedChannel channel,
                               PermissionsServiceGrpc.PermissionsServiceBlockingStub stub,
                               ClientConfiguration config) {
        this.channel = channel;
        this.config = config;
        this.queryClient = new SpiceDBQueryClient(stub, config);
        this.retryHandler = new RetryHandler(config.getMaxRetries());
        this.cache = buildCache(config.getCacheConfiguration());
        log.info("SpiceDBEntitlementsClient created endpoint={}", config.getEngineEndpoint());
    }

    /**
     * Package-private test constructor that accepts a pre-built {@link SpiceDBQueryClient}.
     * Allows unit tests to inject controlled query client behaviour (e.g. throwing gRPC errors)
     * without needing to create or mock the gRPC blocking stub.
     */
    SpiceDBEntitlementsClient(ManagedChannel channel,
                               SpiceDBQueryClient queryClient,
                               ClientConfiguration config) {
        this.channel = channel;
        this.config = config;
        this.queryClient = queryClient;
        this.retryHandler = new RetryHandler(config.getMaxRetries());
        this.cache = buildCache(config.getCacheConfiguration());
        log.info("SpiceDBEntitlementsClient created (test constructor) endpoint={}", config.getEngineEndpoint());
    }

    /**
     * Package-private test constructor that accepts a pre-built {@link SpiceDBQueryClient} and
     * a pre-built {@link RetryHandler}. Allows unit tests to inject a zero-delay retry handler
     * alongside a controlled query client without real network activity.
     */
    SpiceDBEntitlementsClient(ManagedChannel channel,
                               SpiceDBQueryClient queryClient,
                               ClientConfiguration config,
                               RetryHandler retryHandler) {
        this.channel = channel;
        this.config = config;
        this.queryClient = queryClient;
        this.retryHandler = retryHandler;
        this.cache = buildCache(config.getCacheConfiguration());
        log.info("SpiceDBEntitlementsClient created (test constructor) endpoint={}", config.getEngineEndpoint());
    }

    /**
     * Package-private test constructor that accepts a pre-built {@link SpiceDBQueryClient}, a
     * pre-built {@link RetryHandler}, and an explicit {@link CacheProvider}. Allows unit tests
     * to inject a controlled cache alongside other test doubles.
     */
    SpiceDBEntitlementsClient(ManagedChannel channel,
                               SpiceDBQueryClient queryClient,
                               ClientConfiguration config,
                               RetryHandler retryHandler,
                               CacheProvider<EntitlementsCacheKey, EntitlementsResult> cache) {
        this.channel = channel;
        this.config = config;
        this.queryClient = queryClient;
        this.retryHandler = retryHandler;
        this.cache = cache;
        log.info("SpiceDBEntitlementsClient created (test constructor) endpoint={}", config.getEngineEndpoint());
    }

    @Override
    public EntitlementsResult isEntitledTo(SubjectContext subjectContext,
                                            RequestContext requestContext) {
        checkNotClosed();

        if (config.isMonitoring()) {
            return executeInMonitoringMode(subjectContext, requestContext);
        }

        // Cache lookup — only for normal (non-monitoring) enforcement mode
        if (cache != null) {
            EntitlementsCacheKey cacheKey = new EntitlementsCacheKey(subjectContext, requestContext);
            Optional<EntitlementsResult> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                log.debug("Cache hit subjectType={} requestType={}",
                        subjectContext.getClass().getSimpleName(),
                        requestContext.getClass().getSimpleName());
                return cached.get();
            }
        }

        try {
            EntitlementsResult result = retryHandler.executeWithRetry(
                    () -> queryClient.execute(subjectContext, requestContext));

            // Cache the successful result — fallback results are intentionally not cached
            if (cache != null) {
                cache.put(new EntitlementsCacheKey(subjectContext, requestContext), result);
            }

            return result;
        } catch (StatusRuntimeException e) {
            EntitlementsQueryException wrapped = wrapGrpcError(e);
            return applyFallback(subjectContext, requestContext, wrapped);
        }
    }

    /**
     * Executes the entitlement check in monitoring mode.
     *
     * <p>The real SpiceDB check is performed and the result is logged at INFO level, but the
     * method always returns {@code EntitlementsResult(true, monitoring=true)} regardless of
     * the real result. If the SpiceDB call fails, the error is logged at WARN level and the
     * same monitoring result is returned — fallback is not applied in monitoring mode.
     */
    private EntitlementsResult executeInMonitoringMode(SubjectContext subjectContext,
                                                        RequestContext requestContext) {
        try {
            EntitlementsResult realResult = retryHandler.executeWithRetry(
                    () -> queryClient.execute(subjectContext, requestContext));
            log.info("Monitoring mode: isEntitledTo({}, {}) = {}",
                    subjectContext, requestContext, realResult.result());
        } catch (Exception e) {
            log.warn("Monitoring mode: isEntitledTo({}, {}) failed — treating as allowed. error={}",
                    subjectContext, requestContext, e.getMessage());
        }
        return new EntitlementsResult(true, true);
    }

    @Override
    public CompletableFuture<EntitlementsResult> isEntitledToAsync(SubjectContext subjectContext,
                                                                    RequestContext requestContext) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> isEntitledTo(subjectContext, requestContext));
    }

    @Override
    public LookupResult lookupResources(LookupResourcesRequest request) {
        checkNotClosed();
        try {
            return retryHandler.executeWithRetry(() -> queryClient.lookupResources(request));
        } catch (StatusRuntimeException e) {
            throw wrapGrpcError(e);
        }
    }

    @Override
    public CompletableFuture<LookupResult> lookupResourcesAsync(LookupResourcesRequest request) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> lookupResources(request));
    }

    @Override
    public LookupResult lookupSubjects(LookupSubjectsRequest request) {
        checkNotClosed();
        try {
            return retryHandler.executeWithRetry(() -> queryClient.lookupSubjects(request));
        } catch (StatusRuntimeException e) {
            throw wrapGrpcError(e);
        }
    }

    @Override
    public CompletableFuture<LookupResult> lookupSubjectsAsync(LookupSubjectsRequest request) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> lookupSubjects(request));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            // Already closed — idempotent, do nothing
            return;
        }
        log.info("Shutting down SpiceDBEntitlementsClient endpoint={}", config.getEngineEndpoint());

        if (cache != null) {
            cache.invalidateAll();
        }

        channel.shutdown();
        try {
            if (!channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("gRPC channel did not terminate within {}s, forcing shutdown endpoint={}",
                        SHUTDOWN_TIMEOUT_SECONDS, config.getEngineEndpoint());
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while awaiting channel termination, forcing shutdown endpoint={}",
                    config.getEngineEndpoint());
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link CaffeineCacheProvider} from the given configuration, or returns
     * {@code null} if {@code cacheConfiguration} is {@code null} (caching disabled).
     */
    private static CacheProvider<EntitlementsCacheKey, EntitlementsResult> buildCache(
            CacheConfiguration cacheConfiguration) {
        if (cacheConfiguration == null) {
            return null;
        }
        return new CaffeineCacheProvider<>(cacheConfiguration);
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client has been closed");
        }
    }

    /**
     * Wraps a gRPC {@link StatusRuntimeException} into the appropriate SDK exception type.
     * {@code DEADLINE_EXCEEDED} maps to {@link EntitlementsTimeoutException}; all other
     * status codes map to {@link EntitlementsQueryException}.
     */
    private static EntitlementsQueryException wrapGrpcError(StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
            return new EntitlementsTimeoutException("Entitlement check timed out", e);
        }
        return new EntitlementsQueryException("Entitlement check failed: "
                + e.getStatus().getCode(), e);
    }

    /**
     * Applies the configured {@link FallbackStrategy}, if any.
     * If no fallback is configured, rethrows {@code cause}.
     */
    private EntitlementsResult applyFallback(SubjectContext subjectContext,
                                              RequestContext requestContext,
                                              EntitlementsQueryException cause) {
        FallbackStrategy fallback = config.getFallbackStrategy();
        if (fallback == null) {
            log.warn("Entitlement check failed and no fallback configured subjectType={} requestType={}",
                    subjectContext.getClass().getSimpleName(),
                    requestContext.getClass().getSimpleName());
            throw cause;
        }

        log.warn("Entitlement check failed; applying fallback strategy={} subjectType={} requestType={}",
                fallback.getClass().getSimpleName(),
                subjectContext.getClass().getSimpleName(),
                requestContext.getClass().getSimpleName());

        if (fallback instanceof StaticFallback sf) {
            return new EntitlementsResult(sf.result(), false);
        }
        if (fallback instanceof FunctionFallback ff) {
            try {
                return ff.handler().apply(new FallbackContext(subjectContext, requestContext, cause));
            } catch (Exception handlerException) {
                log.warn("Fallback function threw an exception; rethrowing original gRPC error with fallback error as suppressed",
                        handlerException);
                cause.addSuppressed(handlerException);
                throw cause;
            }
        }
        // Should never reach here — FallbackStrategy is sealed
        throw new IllegalStateException("Unknown FallbackStrategy type: " + fallback.getClass().getName());
    }
}
