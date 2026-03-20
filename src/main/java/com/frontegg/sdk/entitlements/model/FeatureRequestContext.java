package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Requests an entitlement check against a named feature flag.
 *
 * <p>The authorization engine checks whether the subject (user or entity) has the
 * {@code entitled} relation to the {@code frontegg_feature} resource identified by
 * {@code featureKey}.
 *
 * <pre>{@code
 * RequestContext request = new FeatureRequestContext("advanced-reporting");
 * }</pre>
 *
 * @param featureKey the unique key of the feature to check; must not be null or blank
 * @since 0.1.0
 */
public record FeatureRequestContext(String featureKey) implements RequestContext {

    /**
     * Compact canonical constructor — validates that {@code featureKey} is not null or blank.
     *
     * @throws NullPointerException     if {@code featureKey} is {@code null}
     * @throws IllegalArgumentException if {@code featureKey} is blank
     */
    public FeatureRequestContext {
        Objects.requireNonNull(featureKey, "featureKey must not be null");
        if (featureKey.isBlank()) {
            throw new IllegalArgumentException("featureKey must not be blank");
        }
    }
}
