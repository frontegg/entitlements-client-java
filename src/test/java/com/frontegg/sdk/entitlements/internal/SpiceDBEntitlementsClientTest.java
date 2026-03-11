package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.PermissionsServiceGrpc;
import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.cache.CacheProvider;
import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import com.frontegg.sdk.entitlements.config.ClientConfiguration;
import com.frontegg.sdk.entitlements.exception.EntitlementsQueryException;
import com.frontegg.sdk.entitlements.exception.EntitlementsTimeoutException;
import com.frontegg.sdk.entitlements.fallback.FallbackContext;
import com.frontegg.sdk.entitlements.fallback.FunctionFallback;
import com.frontegg.sdk.entitlements.fallback.StaticFallback;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.FeatureRequestContext;
import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;
import com.frontegg.sdk.entitlements.model.UserSubjectContext;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Unit tests for {@link SpiceDBEntitlementsClient}.
 *
 * <p>We avoid Mockito inline mocks for gRPC classes (ManagedChannel, blocking stubs) because
 * ByteBuddy cannot instrument these abstract/final classes on JDK 25. Instead, we use
 * minimal hand-written test doubles.
 */
class SpiceDBEntitlementsClientTest {

    /**
     * Minimal ManagedChannel test double.
     * Records which shutdown methods were called and controls awaitTermination behaviour.
     */
    private static class FakeManagedChannel extends ManagedChannel {

        final AtomicBoolean shutdownCalled = new AtomicBoolean(false);
        final AtomicBoolean shutdownNowCalled = new AtomicBoolean(false);
        final AtomicInteger awaitTerminationCallCount = new AtomicInteger(0);
        private final boolean terminatesCleanly;

        FakeManagedChannel(boolean terminatesCleanly) {
            this.terminatesCleanly = terminatesCleanly;
        }

        @Override
        public ManagedChannel shutdown() {
            shutdownCalled.set(true);
            return this;
        }

        @Override
        public boolean isShutdown() {
            return shutdownCalled.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdownCalled.get() && terminatesCleanly;
        }

        @Override
        public ManagedChannel shutdownNow() {
            shutdownNowCalled.set(true);
            return this;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            awaitTerminationCallCount.incrementAndGet();
            return terminatesCleanly;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
            throw new UnsupportedOperationException("not used in unit tests");
        }

        @Override
        public String authority() {
            return "localhost:50051";
        }
    }

    /**
     * A controllable SpiceDBQueryClient subclass that either returns a preset result or
     * throws a preset StatusRuntimeException when execute() is called.
     *
     * <p>Constructed with the package-private two-argument test constructor of
     * {@link SpiceDBQueryClient} (FeatureSpiceDBQuery, PermissionSpiceDBQuery). Here we
     * override execute() directly, bypassing the real query strategies entirely.
     */
    private static class ControlledQueryClient extends SpiceDBQueryClient {

        private EntitlementsResult resultToReturn;
        private StatusRuntimeException errorToThrow;
        final AtomicBoolean executeCalled = new AtomicBoolean(false);
        final AtomicInteger executeCallCount = new AtomicInteger(0);

        /** Creates a query client that returns the given result on execute(). */
        ControlledQueryClient(EntitlementsResult result) {
            super(/* featureQuery */ null, /* permissionQuery */ null);
            this.resultToReturn = result;
        }

        /** Creates a query client that throws the given exception on execute(). */
        ControlledQueryClient(StatusRuntimeException error) {
            super(/* featureQuery */ null, /* permissionQuery */ null);
            this.errorToThrow = error;
        }

        @Override
        EntitlementsResult execute(SubjectContext subject, RequestContext request) {
            executeCalled.set(true);
            executeCallCount.incrementAndGet();
            if (errorToThrow != null) {
                throw errorToThrow;
            }
            return resultToReturn;
        }
    }

    /**
     * A query client that fails for the first {@code failTimes} calls with the supplied error,
     * then succeeds by returning {@code successResult} on all subsequent calls.
     */
    private static class FailThenSucceedQueryClient extends SpiceDBQueryClient {

        private final int failTimes;
        private final StatusRuntimeException errorToThrow;
        private final EntitlementsResult successResult;
        final AtomicInteger callCount = new AtomicInteger(0);

        FailThenSucceedQueryClient(int failTimes, StatusRuntimeException error,
                                   EntitlementsResult successResult) {
            super(/* featureQuery */ null, /* permissionQuery */ null);
            this.failTimes = failTimes;
            this.errorToThrow = error;
            this.successResult = successResult;
        }

        @Override
        EntitlementsResult execute(SubjectContext subject, RequestContext request) {
            if (callCount.incrementAndGet() <= failTimes) {
                throw errorToThrow;
            }
            return successResult;
        }
    }

    // Convenience subject and request contexts reused across tests
    private static final UserSubjectContext SUBJECT =
            new UserSubjectContext("user-1", "tenant-1", Map.of());
    private static final FeatureRequestContext REQUEST =
            new FeatureRequestContext("feature-key");

    private ClientConfiguration config;
    // stub is null — isEntitledTo throws UnsupportedOperationException before ever using it
    private final PermissionsServiceGrpc.PermissionsServiceBlockingStub stub = null;
    private final FakeManagedChannel channel = new FakeManagedChannel(true);

    @BeforeEach
    void setUp() {
        config = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .build();
    }

    // -------------------------------------------------------------------------
    // close()
    // -------------------------------------------------------------------------

    @Test
    void close_shutsDownChannel() throws Exception {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);

        client.close();

        assert ch.shutdownCalled.get() : "shutdown() must have been called";
        assert ch.awaitTerminationCallCount.get() == 1 : "awaitTermination must be called once";
    }

    @Test
    void close_isIdempotent_doesNotThrowOnSecondCall() {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);

        client.close();

        assertDoesNotThrow(client::close);

        // shutdown must be called exactly once (idempotency)
        assert ch.shutdownCalled.get() : "shutdown() should have been called";
        assert ch.awaitTerminationCallCount.get() == 1
                : "awaitTermination should be called exactly once";
    }

    @Test
    void close_whenChannelDoesNotTerminate_callsShutdownNow() {
        FakeManagedChannel ch = new FakeManagedChannel(false);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);

        client.close();

        assert ch.shutdownCalled.get() : "shutdown() must have been called";
        assert ch.shutdownNowCalled.get() : "shutdownNow() must have been called when channel does not terminate";
    }

    // -------------------------------------------------------------------------
    // isEntitledTo — use-after-close
    // -------------------------------------------------------------------------

    @Test
    void isEntitledTo_afterClose_throwsIllegalStateException() {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);
        client.close();

        assertThrows(IllegalStateException.class,
                () -> client.isEntitledTo(
                        new UserSubjectContext("user-1", "tenant-1", Map.of()),
                        new FeatureRequestContext("feature-key")));
    }

    // -------------------------------------------------------------------------
    // isEntitledToAsync — use-after-close
    // checkNotClosed() fires before supplyAsync, so the exception is thrown synchronously
    // -------------------------------------------------------------------------

    @Test
    void isEntitledToAsync_afterClose_throwsIllegalStateException() {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);
        client.close();

        assertThrows(IllegalStateException.class,
                () -> client.isEntitledToAsync(
                        new UserSubjectContext("user-1", "tenant-1", Map.of()),
                        new FeatureRequestContext("feature-key")));
    }

    // -------------------------------------------------------------------------
    // Story 2.5: try-with-resources (AC3)
    // -------------------------------------------------------------------------

    @Test
    void tryWithResources_closesChannelOnExit() {
        FakeManagedChannel ch = new FakeManagedChannel(true);

        try (EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config)) {
            // Client is used inside the try block; the block exits normally.
            assertNotNull(client);
        }

        // The try-with-resources block must have called close(), which calls shutdown().
        assertTrue(ch.shutdownCalled.get(),
                "try-with-resources must trigger shutdown() on block exit");
        assertEquals(1, ch.awaitTerminationCallCount.get(),
                "awaitTermination must be called exactly once on block exit");
    }

    // -------------------------------------------------------------------------
    // Story 2.5: exact exception message on post-close calls (AC4)
    // -------------------------------------------------------------------------

    @Test
    void isEntitledTo_afterClose_exceptionCarriesCorrectMessage() {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);
        client.close();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.isEntitledTo(
                        new UserSubjectContext("user-1", "tenant-1", Map.of()),
                        new FeatureRequestContext("feature-key")));

        assertEquals("Client has been closed", ex.getMessage(),
                "Exception message must be exactly 'Client has been closed'");
    }

    @Test
    void isEntitledToAsync_afterClose_exceptionCarriesCorrectMessage() {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);
        client.close();

        // checkNotClosed() fires synchronously before supplyAsync, so the exception is
        // thrown directly from the isEntitledToAsync call site — not wrapped in the future.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.isEntitledToAsync(
                        new UserSubjectContext("user-1", "tenant-1", Map.of()),
                        new FeatureRequestContext("feature-key")));

        assertEquals("Client has been closed", ex.getMessage(),
                "Async method must use the same message: 'Client has been closed'");
    }

    // -------------------------------------------------------------------------
    // Story 2.5: idempotent close does not call shutdown twice (AC5 detail)
    // -------------------------------------------------------------------------

    @Test
    void close_isIdempotent_shutdownCalledExactlyOnce() {
        FakeManagedChannel ch = new FakeManagedChannel(true);
        EntitlementsClient client = new SpiceDBEntitlementsClient(ch, stub, config);

        client.close();
        client.close();
        client.close();

        assertEquals(1, ch.awaitTerminationCallCount.get(),
                "awaitTermination must be called exactly once regardless of how many times close() is called");
    }

    // =========================================================================
    // Story 2.2: Fallback Strategy
    // =========================================================================

    @Test
    void isEntitledTo_grpcError_withStaticFallbackTrue_returnsAllowed() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .fallbackStrategy(new StaticFallback(true))
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(result.result(), "StaticFallback(true) must return result=true");
        assertFalse(result.monitoring(), "Fallback result must have monitoring=false");
    }

    @Test
    void isEntitledTo_grpcError_withStaticFallbackFalse_returnsDenied() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .fallbackStrategy(new StaticFallback(false))
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertFalse(result.result(), "StaticFallback(false) must return result=false");
        assertFalse(result.monitoring(), "Fallback result must have monitoring=false");
    }

    @Test
    void isEntitledTo_grpcError_withFunctionFallback_invokesHandlerWithCorrectContext() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        AtomicReference<FallbackContext> capturedContext = new AtomicReference<>();
        FunctionFallback fallback = new FunctionFallback(ctx -> {
            capturedContext.set(ctx);
            return EntitlementsResult.denied();
        });

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .fallbackStrategy(fallback)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        client.isEntitledTo(SUBJECT, REQUEST);

        FallbackContext ctx = capturedContext.get();
        assertNotNull(ctx, "FallbackContext must have been passed to the handler");
        assertSame(SUBJECT, ctx.subjectContext(), "FallbackContext must contain the original subject");
        assertSame(REQUEST, ctx.requestContext(), "FallbackContext must contain the original request");
        assertNotNull(ctx.error(), "FallbackContext must contain the wrapped error");
        assertInstanceOf(EntitlementsQueryException.class, ctx.error(),
                "FallbackContext error must be an EntitlementsQueryException");
    }

    @Test
    void isEntitledTo_grpcError_withNoFallback_throwsEntitlementsQueryException() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        // config has no fallback strategy (default)
        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, config);

        assertThrows(EntitlementsQueryException.class,
                () -> client.isEntitledTo(SUBJECT, REQUEST),
                "Without fallback a gRPC error must propagate as EntitlementsQueryException");
    }

    @Test
    void isEntitledTo_grpcDeadlineExceeded_withNoFallback_throwsEntitlementsTimeoutException() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, config);

        assertThrows(EntitlementsTimeoutException.class,
                () -> client.isEntitledTo(SUBJECT, REQUEST),
                "DEADLINE_EXCEEDED without fallback must propagate as EntitlementsTimeoutException");
    }

    @Test
    void isEntitledTo_functionFallbackThrows_wrapsOriginalGrpcErrorWithSuppressed() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        RuntimeException handlerException = new RuntimeException("handler blew up");
        FunctionFallback fallback = new FunctionFallback(ctx -> {
            throw handlerException;
        });

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .fallbackStrategy(fallback)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        EntitlementsQueryException thrown = assertThrows(EntitlementsQueryException.class,
                () -> client.isEntitledTo(SUBJECT, REQUEST),
                "When FunctionFallback throws, EntitlementsQueryException must be thrown");

        // The handler exception must be present as a suppressed exception
        Throwable[] suppressed = thrown.getSuppressed();
        assertEquals(1, suppressed.length, "Exactly one suppressed exception expected");
        assertSame(handlerException, suppressed[0],
                "The handler exception must be the suppressed exception on the thrown error");
    }

    // =========================================================================
    // Story 2.3: Monitoring Mode
    // =========================================================================

    @Test
    void isEntitledTo_monitoringTrue_checkSucceeds_returnsAllowedWithMonitoringFlag() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .monitoring(true)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(result.result(), "Monitoring mode must always return result=true");
        assertTrue(result.monitoring(), "Monitoring mode must set monitoring=true on result");
        assertTrue(queryClient.executeCalled.get(),
                "Real SpiceDB check must be performed in monitoring mode");
    }

    @Test
    void isEntitledTo_monitoringTrue_checkReturnsFalse_stillReturnsAllowed() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.denied());

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .monitoring(true)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(result.result(),
                "Monitoring mode must return result=true even when real check returns false");
        assertTrue(result.monitoring(), "Monitoring mode must set monitoring=true on result");
    }

    @Test
    void isEntitledTo_monitoringTrue_checkFails_returnsAllowedWithMonitoringFlag() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .monitoring(true)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        EntitlementsResult result = assertDoesNotThrow(
                () -> client.isEntitledTo(SUBJECT, REQUEST),
                "Monitoring mode must not throw even when the SpiceDB call fails");

        assertTrue(result.result(),
                "Monitoring mode must return result=true when the SpiceDB call fails");
        assertTrue(result.monitoring(),
                "Monitoring mode must set monitoring=true on result when SpiceDB call fails");
    }

    @Test
    void isEntitledTo_monitoringFalse_normalBehaviorApplies() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.denied());

        // config has monitoring=false (default)
        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, config);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertFalse(result.result(), "Normal mode must return the real result (false)");
        assertFalse(result.monitoring(), "Normal mode must return monitoring=false");
    }

    // =========================================================================
    // Story 2.4: Retry Logic — integration with SpiceDBEntitlementsClient
    // =========================================================================

    /**
     * Builds a zero-delay {@link RetryHandler} so retry integration tests finish instantly.
     */
    private static RetryHandler zeroDelayHandler(int maxRetries) {
        return new RetryHandler(maxRetries) {
            @Override
            long computeBackoff(int attempt) {
                return 0L;
            }
        };
    }

    @Test
    void isEntitledTo_unavailable_retriedBeforeFallbackApplied_succeedsAfterOneRetry() {
        // The query client fails once with UNAVAILABLE, then succeeds.
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        FailThenSucceedQueryClient queryClient =
                new FailThenSucceedQueryClient(1, unavailable, EntitlementsResult.allowed());

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .maxRetries(3)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, cfg, zeroDelayHandler(cfg.getMaxRetries()));

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertAll(
                () -> assertTrue(result.result(), "Result must be allowed after retry succeeds"),
                () -> assertFalse(result.monitoring(), "Result must not be in monitoring mode"),
                () -> assertEquals(2, queryClient.callCount.get(),
                        "Query must be attempted twice: once failing, once succeeding")
        );
    }

    @Test
    void isEntitledTo_unavailable_allRetriesExhausted_fallbackApplied() {
        // The query client always throws UNAVAILABLE — all retries exhausted → fallback fires.
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(unavailable);

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .maxRetries(2)
                .fallbackStrategy(new StaticFallback(false))
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, cfg, zeroDelayHandler(cfg.getMaxRetries()));

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertAll(
                () -> assertFalse(result.result(),
                        "StaticFallback(false) must return denied after all retries exhausted"),
                () -> assertEquals(3, queryClient.executeCallCount.get(),
                        "Query must be attempted 3 times: 1 initial + 2 retries")
        );
    }

    @Test
    void isEntitledTo_unavailable_allRetriesExhausted_noFallback_throwsEntitlementsQueryException() {
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(unavailable);

        // config has no fallback strategy and maxRetries=1
        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .maxRetries(1)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, cfg, zeroDelayHandler(cfg.getMaxRetries()));

        assertThrows(EntitlementsQueryException.class,
                () -> client.isEntitledTo(SUBJECT, REQUEST),
                "Without fallback, exhausted retries must propagate as EntitlementsQueryException");

        assertEquals(2, queryClient.executeCallCount.get(),
                "Query must be attempted 2 times: 1 initial + 1 retry");
    }

    // =========================================================================
    // Story 4.1: Caching — CacheProvider integration in SpiceDBEntitlementsClient
    // =========================================================================

    /**
     * Simple in-memory {@link CacheProvider} backed by a {@link ConcurrentHashMap}.
     * Tracks get/put/invalidateAll call counts for assertion.
     */
    private static class TrackingCacheProvider
            implements CacheProvider<EntitlementsCacheKey, EntitlementsResult> {

        private final ConcurrentHashMap<EntitlementsCacheKey, EntitlementsResult> store =
                new ConcurrentHashMap<>();
        final AtomicInteger getCallCount = new AtomicInteger(0);
        final AtomicInteger putCallCount = new AtomicInteger(0);
        final AtomicInteger invalidateAllCallCount = new AtomicInteger(0);

        @Override
        public Optional<EntitlementsResult> get(EntitlementsCacheKey key) {
            getCallCount.incrementAndGet();
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(EntitlementsCacheKey key, EntitlementsResult value) {
            putCallCount.incrementAndGet();
            store.put(key, value);
        }

        @Override
        public void invalidate(EntitlementsCacheKey key) {
            store.remove(key);
        }

        @Override
        public void invalidateAll() {
            invalidateAllCallCount.incrementAndGet();
            store.clear();
        }
    }

    @Test
    void isEntitledTo_cacheEnabled_cacheMiss_callsGrpcAndStoresResult() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());
        TrackingCacheProvider cache = new TrackingCacheProvider();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, config, zeroDelayHandler(config.getMaxRetries()), cache);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(result.result(), "Cache miss must return the real gRPC result");
        assertTrue(queryClient.executeCalled.get(), "Cache miss must invoke gRPC query");
        assertEquals(1, cache.putCallCount.get(), "Successful result must be stored in cache");
    }

    @Test
    void isEntitledTo_cacheEnabled_cacheHit_doesNotCallGrpc() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());
        TrackingCacheProvider cache = new TrackingCacheProvider();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, config, zeroDelayHandler(config.getMaxRetries()), cache);

        // First call — miss, populates cache
        client.isEntitledTo(SUBJECT, REQUEST);
        // Second call — hit, must not call gRPC again
        EntitlementsResult second = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(second.result(), "Cache hit must return the cached result");
        assertEquals(1, queryClient.executeCallCount.get(),
                "gRPC query must be called exactly once; second call must be served from cache");
    }

    @Test
    void isEntitledTo_cacheEnabled_differentKeys_missOnEach() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());
        TrackingCacheProvider cache = new TrackingCacheProvider();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, config, zeroDelayHandler(config.getMaxRetries()), cache);

        UserSubjectContext subjectA = new UserSubjectContext("user-A", "tenant-1", Map.of());
        UserSubjectContext subjectB = new UserSubjectContext("user-B", "tenant-1", Map.of());
        FeatureRequestContext requestX = new FeatureRequestContext("feature-x");

        client.isEntitledTo(subjectA, requestX);
        client.isEntitledTo(subjectB, requestX);

        assertEquals(2, queryClient.executeCallCount.get(),
                "Different subject contexts must each produce a cache miss");
        assertEquals(2, cache.putCallCount.get(),
                "Each cache miss must result in a put()");
    }

    @Test
    void isEntitledTo_cacheEnabled_fallbackResult_notStored() {
        StatusRuntimeException grpcError = new StatusRuntimeException(Status.UNAVAILABLE);
        ControlledQueryClient queryClient = new ControlledQueryClient(grpcError);
        TrackingCacheProvider cache = new TrackingCacheProvider();

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .fallbackStrategy(new StaticFallback(true))
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, cfg, zeroDelayHandler(cfg.getMaxRetries()), cache);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(result.result(), "StaticFallback(true) must return result=true");
        assertEquals(0, cache.putCallCount.get(),
                "Fallback results must NOT be stored in the cache");
    }

    @Test
    void isEntitledTo_monitoringMode_resultNotCached() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.denied());
        TrackingCacheProvider cache = new TrackingCacheProvider();

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .monitoring(true)
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, cfg, zeroDelayHandler(cfg.getMaxRetries()), cache);

        EntitlementsResult result = client.isEntitledTo(SUBJECT, REQUEST);

        assertTrue(result.result(), "Monitoring mode must always return true");
        assertTrue(result.monitoring(), "Monitoring mode must set monitoring=true");
        assertEquals(0, cache.putCallCount.get(),
                "Monitoring mode results must NOT be stored in the cache");
        assertEquals(0, cache.getCallCount.get(),
                "Cache must NOT be consulted in monitoring mode");
    }

    @Test
    void close_withCache_invalidatesAllEntries() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());
        TrackingCacheProvider cache = new TrackingCacheProvider();

        SpiceDBEntitlementsClient client = new SpiceDBEntitlementsClient(
                channel, queryClient, config, zeroDelayHandler(config.getMaxRetries()), cache);

        // Populate the cache
        client.isEntitledTo(SUBJECT, REQUEST);
        assertEquals(1, cache.putCallCount.get(), "Pre-condition: cache must be populated");

        client.close();

        assertEquals(1, cache.invalidateAllCallCount.get(),
                "close() must call invalidateAll() on the cache");
    }

    @Test
    void isEntitledTo_noCacheConfiguration_doesNotUseCacheAtAll() {
        // config has no cacheConfiguration (default null) — no CacheProvider is created
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, config);

        // Two calls with the same key must both hit gRPC since there is no cache
        client.isEntitledTo(SUBJECT, REQUEST);
        client.isEntitledTo(SUBJECT, REQUEST);

        assertEquals(2, queryClient.executeCallCount.get(),
                "Without a cache every call must go through to gRPC");
    }

    @Test
    void isEntitledTo_cacheConfigurationSet_createsCacheInternally() {
        ControlledQueryClient queryClient = new ControlledQueryClient(EntitlementsResult.allowed());

        ClientConfiguration cfg = ClientConfiguration.builder()
                .engineEndpoint("localhost:50051")
                .engineToken("test-token")
                .useTls(false)
                .cacheConfiguration(new CacheConfiguration(1000, Duration.ofMinutes(5)))
                .build();

        EntitlementsClient client = new SpiceDBEntitlementsClient(channel, queryClient, cfg);

        // First call — cache miss, should call gRPC
        client.isEntitledTo(SUBJECT, REQUEST);
        assertEquals(1, queryClient.executeCallCount.get(), "First call must hit gRPC");

        // Second call — cache hit, should NOT call gRPC again
        client.isEntitledTo(SUBJECT, REQUEST);
        assertEquals(1, queryClient.executeCallCount.get(),
                "Second call must be served from the internal Caffeine cache");
    }
}
