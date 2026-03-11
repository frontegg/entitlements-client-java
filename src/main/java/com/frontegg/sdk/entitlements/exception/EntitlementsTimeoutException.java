package com.frontegg.sdk.entitlements.exception;

/**
 * Thrown when the gRPC deadline configured via
 * {@link com.frontegg.sdk.entitlements.config.ClientConfiguration#getRequestTimeout()} or
 * {@link com.frontegg.sdk.entitlements.config.ClientConfiguration#getBulkRequestTimeout()} is
 * exceeded and no {@link com.frontegg.sdk.entitlements.fallback.FallbackStrategy} is configured.
 *
 * <p>This is a specialisation of {@link EntitlementsQueryException} so callers can catch either
 * exception type depending on how granular their error handling needs to be:
 *
 * <pre>{@code
 * try {
 *     EntitlementsResult result = client.isEntitledTo(subject, request);
 * } catch (EntitlementsTimeoutException e) {
 *     // handle timeout specifically — maybe retry with a different strategy
 * } catch (EntitlementsQueryException e) {
 *     // handle all other engine errors
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
public class EntitlementsTimeoutException extends EntitlementsQueryException {

    /**
     * Constructs a new {@code EntitlementsTimeoutException} with no detail message.
     *
     * @since 0.1.0
     */
    public EntitlementsTimeoutException() {
        super();
    }

    /**
     * Constructs a new {@code EntitlementsTimeoutException} with the specified detail message.
     *
     * @param message the detail message
     * @since 0.1.0
     */
    public EntitlementsTimeoutException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code EntitlementsTimeoutException} with the specified detail message
     * and cause.
     *
     * @param message the detail message
     * @param cause   the root-cause exception (typically a gRPC {@code StatusRuntimeException}
     *                with status {@code DEADLINE_EXCEEDED})
     * @since 0.1.0
     */
    public EntitlementsTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code EntitlementsTimeoutException} with the specified cause.
     *
     * @param cause the root-cause exception
     * @since 0.1.0
     */
    public EntitlementsTimeoutException(Throwable cause) {
        super(cause);
    }
}
