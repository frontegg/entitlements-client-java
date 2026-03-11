package com.frontegg.sdk.entitlements.exception;

/**
 * Thrown when a {@link com.frontegg.sdk.entitlements.config.ClientConfiguration} field is
 * present but its value is invalid (e.g. a negative timeout, a malformed endpoint URL).
 *
 * <p>The exception message should be actionable and describe both the invalid value and the
 * correction needed.
 *
 * @since 0.1.0
 */
public class ConfigurationInvalidException extends EntitlementsException {

    /**
     * Constructs a {@code ConfigurationInvalidException} with the given descriptive message.
     *
     * @param message a human-readable description of the validation failure and how to fix it
     * @since 0.1.0
     */
    public ConfigurationInvalidException(String message) {
        super(message);
    }
}
