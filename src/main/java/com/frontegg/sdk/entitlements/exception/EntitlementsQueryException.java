package com.frontegg.sdk.entitlements.exception;

/**
 * Thrown when the authorization engine returns an error (other than a deadline exceeded) and no
 * {@link com.frontegg.sdk.entitlements.fallback.FallbackStrategy} is configured, or when the
 * fallback itself fails.
 *
 * <p>The underlying gRPC {@code StatusRuntimeException} is available via {@link #getCause()} for
 * diagnostics but must not be relied upon as part of the public contract — the cause type may
 * change if the transport layer is replaced.
 *
 * @since 0.1.0
 */
public class EntitlementsQueryException extends EntitlementsException {

    /**
     * Constructs a new {@code EntitlementsQueryException} with no detail message.
     *
     * @since 0.1.0
     */
    public EntitlementsQueryException() {
        super();
    }

    /**
     * Constructs a new {@code EntitlementsQueryException} with the specified detail message.
     *
     * @param message the detail message
     * @since 0.1.0
     */
    public EntitlementsQueryException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code EntitlementsQueryException} with the specified detail message and
     * cause.
     *
     * @param message the detail message
     * @param cause   the root-cause exception (typically a gRPC {@code StatusRuntimeException})
     * @since 0.1.0
     */
    public EntitlementsQueryException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code EntitlementsQueryException} with the specified cause.
     *
     * @param cause the root-cause exception
     * @since 0.1.0
     */
    public EntitlementsQueryException(Throwable cause) {
        super(cause);
    }
}
