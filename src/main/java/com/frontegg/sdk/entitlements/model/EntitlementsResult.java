package com.frontegg.sdk.entitlements.model;

/**
 * The result of an entitlement check performed by {@link com.frontegg.sdk.entitlements.EntitlementsClient}.
 *
 * <p>Use the factory methods {@link #allowed()} and {@link #denied()} for the common cases, or
 * construct directly when both fields need explicit values.
 *
 * <pre>{@code
 * EntitlementsResult result = client.isEntitledTo(subject, request);
 * if (result.result()) {
 *     // subject is entitled — grant access
 * }
 * }</pre>
 *
 * @param result     {@code true} when the subject is entitled to the requested resource or
 *                   permission; {@code false} otherwise
 * @param monitoring {@code true} when the check was executed in monitoring mode (the engine
 *                   always returns allowed in monitoring mode, but this flag lets callers
 *                   distinguish monitoring results from real results); {@code false} for normal
 *                   checks
 * @since 0.1.0
 */
public record EntitlementsResult(boolean result, boolean monitoring) {

    /**
     * Creates an {@code EntitlementsResult} representing a granted (allowed) entitlement in
     * normal (non-monitoring) mode.
     *
     * @return a result with {@code result=true} and {@code monitoring=false}
     * @since 0.1.0
     */
    public static EntitlementsResult allowed() {
        return new EntitlementsResult(true, false);
    }

    /**
     * Creates an {@code EntitlementsResult} representing a denied entitlement in normal
     * (non-monitoring) mode.
     *
     * @return a result with {@code result=false} and {@code monitoring=false}
     * @since 0.1.0
     */
    public static EntitlementsResult denied() {
        return new EntitlementsResult(false, false);
    }
}
