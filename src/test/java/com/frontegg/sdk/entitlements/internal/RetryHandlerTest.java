package com.frontegg.sdk.entitlements.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RetryHandler}.
 *
 * <p>Tests avoid real {@code Thread.sleep} calls by using a subclass that overrides
 * {@link RetryHandler#computeBackoff} to return 0, making the suite run without delays.
 */
class RetryHandlerTest {

    // -------------------------------------------------------------------------
    // Test double: zero-delay retry handler
    // -------------------------------------------------------------------------

    /**
     * Subclass of {@link RetryHandler} that returns a 0 ms backoff, eliminating sleep
     * overhead in unit tests while still exercising all retry logic paths.
     */
    private static class ZeroDelayRetryHandler extends RetryHandler {

        final List<Long> recordedBackoffs = new ArrayList<>();

        ZeroDelayRetryHandler(int maxRetries) {
            super(maxRetries);
        }

        @Override
        long computeBackoff(int attempt) {
            long real = super.computeBackoff(attempt);
            recordedBackoffs.add(real);
            return 0L; // skip actual sleep in tests
        }
    }

    // -------------------------------------------------------------------------
    // Success path
    // -------------------------------------------------------------------------

    @Test
    void execute_succeedsOnFirstTry_returnsResultWithoutRetrying() {
        ZeroDelayRetryHandler handler = new ZeroDelayRetryHandler(3);
        AtomicInteger callCount = new AtomicInteger(0);

        String result = handler.executeWithRetry(() -> {
            callCount.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, callCount.get(), "Action must be called exactly once on success");
        assertTrue(handler.recordedBackoffs.isEmpty(), "No backoff should be recorded on success");
    }

    // -------------------------------------------------------------------------
    // Retryable status codes
    // -------------------------------------------------------------------------

    @Test
    void execute_unavailableOnFirstTry_succeedsOnSecond_returnsResult() {
        ZeroDelayRetryHandler handler = new ZeroDelayRetryHandler(3);
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        AtomicInteger callCount = new AtomicInteger(0);

        String result = handler.executeWithRetry(() -> {
            if (callCount.incrementAndGet() == 1) {
                throw unavailable;
            }
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(2, callCount.get(), "Action must be called twice: once failing, once succeeding");
        assertEquals(1, handler.recordedBackoffs.size(), "Exactly one backoff between attempts");
    }

    @Test
    void execute_deadlineExceededOnFirstTry_succeedsOnSecond_returnsResult() {
        ZeroDelayRetryHandler handler = new ZeroDelayRetryHandler(3);
        StatusRuntimeException deadline = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        AtomicInteger callCount = new AtomicInteger(0);

        String result = handler.executeWithRetry(() -> {
            if (callCount.incrementAndGet() == 1) {
                throw deadline;
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, callCount.get());
    }

    @Test
    void execute_allRetriesExhausted_throwsLastException() {
        ZeroDelayRetryHandler handler = new ZeroDelayRetryHandler(2);
        StatusRuntimeException last = new StatusRuntimeException(Status.UNAVAILABLE);
        AtomicInteger callCount = new AtomicInteger(0);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                handler.executeWithRetry(() -> {
                    callCount.incrementAndGet();
                    throw last;
                }));

        assertSame(last, thrown, "The last thrown exception must propagate unchanged");
        // initial attempt + 2 retries = 3 total calls
        assertEquals(3, callCount.get(), "Action must be called 1 initial + 2 retries = 3 times");
        assertEquals(2, handler.recordedBackoffs.size(), "Two backoffs recorded for two retries");
    }

    @Test
    void execute_maxRetriesZero_throwsImmediatelyWithoutRetrying() {
        ZeroDelayRetryHandler handler = new ZeroDelayRetryHandler(0);
        StatusRuntimeException error = new StatusRuntimeException(Status.UNAVAILABLE);
        AtomicInteger callCount = new AtomicInteger(0);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                handler.executeWithRetry(() -> {
                    callCount.incrementAndGet();
                    throw error;
                }));

        assertSame(error, thrown);
        assertEquals(1, callCount.get(), "With maxRetries=0 the action is attempted once then thrown");
        assertTrue(handler.recordedBackoffs.isEmpty(), "No backoff with maxRetries=0");
    }

    // -------------------------------------------------------------------------
    // Non-retryable status codes — thrown immediately
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {
            "PERMISSION_DENIED",
            "UNAUTHENTICATED",
            "INVALID_ARGUMENT",
            "NOT_FOUND"
    })
    void execute_nonRetryableStatusCode_thrownImmediatelyWithoutRetry(Status.Code code) {
        ZeroDelayRetryHandler handler = new ZeroDelayRetryHandler(3);
        StatusRuntimeException error = new StatusRuntimeException(Status.fromCode(code));
        AtomicInteger callCount = new AtomicInteger(0);

        StatusRuntimeException thrown = assertThrows(StatusRuntimeException.class, () ->
                handler.executeWithRetry(() -> {
                    callCount.incrementAndGet();
                    throw error;
                }));

        assertSame(error, thrown, "Non-retryable exception must propagate unchanged");
        assertEquals(1, callCount.get(), code + " must not trigger a retry");
        assertTrue(handler.recordedBackoffs.isEmpty(),
                "No backoff must be recorded for non-retryable status " + code);
    }

    // -------------------------------------------------------------------------
    // Exponential backoff timing verification
    // -------------------------------------------------------------------------

    @Test
    void computeBackoff_exponentiallyIncreases_withJitter() {
        // Use the real handler (not zero-delay) solely to test the formula.
        RetryHandler handler = new RetryHandler(5);

        long backoff0 = handler.computeBackoff(0); // base * 2^0 = 200ms + jitter
        long backoff1 = handler.computeBackoff(1); // base * 2^1 = 400ms + jitter
        long backoff2 = handler.computeBackoff(2); // base * 2^2 = 800ms + jitter
        long backoff3 = handler.computeBackoff(3); // base * 2^3 = 1600ms + jitter
        long backoff4 = handler.computeBackoff(4); // min(3200, 2000) + jitter = 2000ms + jitter

        // Each uncapped delay must be >= the base without jitter
        assertTrue(backoff0 >= RetryHandler.BASE_DELAY_MS,
                "attempt 0 backoff must be >= " + RetryHandler.BASE_DELAY_MS);
        assertTrue(backoff1 >= RetryHandler.BASE_DELAY_MS * 2,
                "attempt 1 backoff must be >= " + RetryHandler.BASE_DELAY_MS * 2);
        assertTrue(backoff2 >= RetryHandler.BASE_DELAY_MS * 4,
                "attempt 2 backoff must be >= " + RetryHandler.BASE_DELAY_MS * 4);

        // Max backoff cap must be respected (plus jitter)
        long maxWithJitter = RetryHandler.MAX_BACKOFF_MS + RetryHandler.JITTER_BOUND_MS;
        assertTrue(backoff3 <= maxWithJitter,
                "attempt 3 backoff must be <= " + maxWithJitter + ", got " + backoff3);
        assertTrue(backoff4 <= maxWithJitter,
                "attempt 4 backoff must be <= " + maxWithJitter + ", got " + backoff4);

        // All values must be positive
        assertTrue(backoff0 > 0);
        assertTrue(backoff1 > 0);
        assertTrue(backoff2 > 0);
    }

    @Test
    void execute_backoffIncreasesAcrossRetries() {
        // Capture the actual computed backoffs (before they are overridden to 0) and
        // verify that later attempts produce equal-or-greater delays than earlier ones.
        List<Long> realBackoffs = new ArrayList<>();
        RetryHandler handler = new RetryHandler(3) {
            @Override
            long computeBackoff(int attempt) {
                long v = super.computeBackoff(attempt);
                realBackoffs.add(v);
                return 0L; // still skip actual sleep
            }
        };

        StatusRuntimeException error = new StatusRuntimeException(Status.UNAVAILABLE);
        assertThrows(StatusRuntimeException.class, () ->
                handler.executeWithRetry(() -> {
                    throw error;
                }));

        assertEquals(3, realBackoffs.size(), "Three backoffs for three retries");
        // attempt=0 → 200ms base; attempt=1 → 400ms base; attempt=2 → 800ms base.
        // With jitter the exact values can differ, but the base components grow.
        // The strict assertion: each computed value must be at least the base delay for that attempt.
        assertTrue(realBackoffs.get(0) >= RetryHandler.BASE_DELAY_MS,
                "First retry backoff must be >= base delay");
        assertTrue(realBackoffs.get(1) >= RetryHandler.BASE_DELAY_MS * 2,
                "Second retry backoff must be >= 2x base delay");
        assertTrue(realBackoffs.get(2) >= RetryHandler.BASE_DELAY_MS * 4,
                "Third retry backoff must be >= 4x base delay");
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void constructor_negativeMaxRetries_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new RetryHandler(-1));
    }
}
