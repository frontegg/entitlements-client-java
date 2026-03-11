package com.frontegg.sdk.entitlements.exception;

/**
 * Thrown when a required {@link com.frontegg.sdk.entitlements.config.ClientConfiguration}
 * field is absent at build time.
 *
 * <p>The exception message is intentionally actionable, telling the caller exactly which field
 * is missing and how to supply it:
 *
 * <pre>
 * engineEndpoint is required. Set via ClientConfiguration.builder().engineEndpoint(...)
 * </pre>
 *
 * <p>Use {@link #getFieldName()} to retrieve the missing field name programmatically (e.g. for
 * structured logging or UI error mapping).
 *
 * @since 0.1.0
 */
public class ConfigurationMissingException extends EntitlementsException {

    private final String fieldName;

    /**
     * Constructs a {@code ConfigurationMissingException} for the named configuration field.
     *
     * @param fieldName the name of the required field that is missing; must not be {@code null}
     * @since 0.1.0
     */
    public ConfigurationMissingException(String fieldName) {
        super(fieldName + " is required. Set via ClientConfiguration.builder()."
                + fieldName + "(...)");
        this.fieldName = fieldName;
    }

    /**
     * Returns the name of the configuration field that is missing.
     *
     * @return the field name; never {@code null}
     * @since 0.1.0
     */
    public String getFieldName() {
        return fieldName;
    }
}
