package com.frontegg.sdk.entitlements.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Executes a gRPC action with exponential backoff and jitter for transient failures.
 *
 * <p>Only {@code UNAVAILABLE} and {@code DEADLINE_EXCEEDED} status codes are considered
 * retryable. All other status codes ({@code PERMISSION_DENIED}, {@code UNAUTHENTICATED},
 * {@code INVALID_ARGUMENT}, {@code NOT_FOUND}, etc.) are thrown immediately without retrying.
 *
 * <p>Backoff formula: {@code min(baseDelay * 2^attempt, maxBackoff) + jitter(0..100ms)}
 * where {@code baseDelay=200ms} and {@code maxBackoff=2000ms}.
 *
 * <p>This class is package-private and is only used internally by
 * {@link SpiceDBEntitlementsClient}.
 */
class RetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);

    static final long BASE_DELAY_MS = 200L;
    static final long MAX_BACKOFF_MS = 2_000L;
    static final int JITTER_BOUND_MS = 100;

    private final int maxRetries;

    /**
     * Creates a {@code RetryHandler} with the given maximum retry count.
     *
     * @param maxRetries the maximum number of retry attempts after the initial failure;
     *                   must be &gt;= 0. When 0, no retries are attempted.
     */
    RetryHandler(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
        }
        this.maxRetries = maxRetries;
    }

    /**
     * Executes {@code action} and retries on retryable gRPC status codes.
     *
     * <p>On a retryable failure the thread sleeps for an exponentially increasing delay plus
     * random jitter before re-invoking {@code action}. After all retries are exhausted the
     * last {@link StatusRuntimeException} is re-thrown.
     *
     * <p>Non-retryable status codes are thrown immediately on first occurrence without sleeping.
     *
     * @param action the gRPC call to execute; must not be {@code null}
     * @param <T>    the return type of the action
     * @return the result of the action on success
     * @throws StatusRuntimeException if all attempts fail (for retryable codes) or on the
     *                                first attempt (for non-retryable codes)
     */
    <T> T executeWithRetry(Supplier<T> action) {
        StatusRuntimeException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (StatusRuntimeException e) {
                if (!isRetryable(e.getStatus().getCode())) {
                    throw e;
                }

                lastException = e;

                if (attempt < maxRetries) {
                    long backoffMs = computeBackoff(attempt);
                    log.warn("Retrying gRPC call, attempt {}/{}, status={}, backoff={}ms",
                            attempt + 1, maxRetries, e.getStatus().getCode(), backoffMs);
                    sleep(backoffMs);
                }
            }
        }

        // All retries exhausted — re-throw the last retryable exception.
        throw lastException;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the given gRPC status code should trigger a retry.
     * Only {@code UNAVAILABLE} and {@code DEADLINE_EXCEEDED} are retryable.
     */
    private static boolean isRetryable(Status.Code code) {
        return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
    }

    /**
     * Computes the sleep duration for {@code attempt} (0-based).
     * Formula: {@code min(baseDelay * 2^attempt, maxBackoff) + jitter}
     */
    long computeBackoff(int attempt) {
        long exponential = BASE_DELAY_MS * (1L << attempt); // 200, 400, 800, ...
        long capped = Math.min(exponential, MAX_BACKOFF_MS);
        long jitter = ThreadLocalRandom.current().nextLong(0, JITTER_BOUND_MS + 1);
        return capped + jitter;
    }

    /**
     * Sleeps for {@code millis} milliseconds, restoring the interrupt flag if interrupted.
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new StatusRuntimeException(Status.CANCELLED.withDescription("Retry interrupted").withCause(ie));
        }
    }
}
