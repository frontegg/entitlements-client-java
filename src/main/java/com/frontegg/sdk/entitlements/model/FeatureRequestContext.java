package com.frontegg.sdk.entitlements.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Requests an entitlement check against a named feature flag.
 *
 * <p>The authorization engine checks whether the subject (user or entity) has the
 * {@code entitled} relation to the {@code frontegg_feature} resource identified by
 * {@code featureKey}.
 *
 * <p>The optional {@code at} parameter allows checking entitlement at a specific point in time
 * (past or future). When {@code at} is non-null it is forwarded to the authorization engine as
 * an {@code "at"} field in the caveat context using ISO-8601 string format. A {@code null}
 * value means "check at the current time".
 *
 * <pre>{@code
 * // Check at current time
 * RequestContext request = new FeatureRequestContext("advanced-reporting");
 *
 * // Check at a specific point in time
 * RequestContext request = new FeatureRequestContext("advanced-reporting",
 *         Instant.parse("2026-01-01T00:00:00Z"));
 * }</pre>
 *
 * @param featureKey the unique key of the feature to check; must not be null or blank
 * @param at         the point in time at which to evaluate entitlement; {@code null} means
 *                   current time
 * @since 0.1.0
 */
public record FeatureRequestContext(String featureKey, Instant at) implements RequestContext {

    /**
     * Compact canonical constructor — validates that {@code featureKey} is not null or blank.
     * {@code at} is nullable; {@code null} means "check at the current time".
     *
     * @throws NullPointerException     if {@code featureKey} is {@code null}
     * @throws IllegalArgumentException if {@code featureKey} is blank
     */
    public FeatureRequestContext {
        Objects.requireNonNull(featureKey, "featureKey must not be null");
        if (featureKey.isBlank()) {
            throw new IllegalArgumentException("featureKey must not be blank");
        }
        // at is nullable — null means "check at current time"
    }

    /**
     * Convenience constructor for checking at the current time.
     *
     * @param featureKey the unique key of the feature to check; must not be null or blank
     */
    public FeatureRequestContext(String featureKey) {
        this(featureKey, null);
    }
}
