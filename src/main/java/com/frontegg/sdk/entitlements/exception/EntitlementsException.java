package com.frontegg.sdk.entitlements.exception;

/**
 * Base class for all unchecked exceptions thrown by the Frontegg entitlements SDK.
 *
 * <p>The SDK uses unchecked exceptions exclusively. The full exception hierarchy is:
 *
 * <pre>
 * EntitlementsException (RuntimeException)
 * ├── ConfigurationMissingException   — a required configuration field is absent
 * ├── ConfigurationInvalidException   — a configuration field has an invalid value
 * └── EntitlementsQueryException      — the authorization engine returned an error
 *     └── EntitlementsTimeoutException — the gRPC deadline was exceeded
 * </pre>
 *
 * @since 0.1.0
 */
public class EntitlementsException extends RuntimeException {

    /**
     * Constructs a new {@code EntitlementsException} with no detail message.
     *
     * @since 0.1.0
     */
    public EntitlementsException() {
        super();
    }

    /**
     * Constructs a new {@code EntitlementsException} with the specified detail message.
     *
     * @param message the detail message
     * @since 0.1.0
     */
    public EntitlementsException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code EntitlementsException} with the specified detail message and
     * cause.
     *
     * @param message the detail message
     * @param cause   the cause
     * @since 0.1.0
     */
    public EntitlementsException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code EntitlementsException} with the specified cause.
     *
     * @param cause the cause
     * @since 0.1.0
     */
    public EntitlementsException(Throwable cause) {
        super(cause);
    }
}
