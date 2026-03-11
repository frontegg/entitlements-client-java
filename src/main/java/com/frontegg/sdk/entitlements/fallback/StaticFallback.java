package com.frontegg.sdk.entitlements.fallback;

import com.frontegg.sdk.entitlements.model.EntitlementsResult;

/**
 * A {@link FallbackStrategy} that always returns a fixed entitlement result regardless of the
 * error or the request context.
 *
 * <p>This is the simplest fallback strategy and is suitable when the desired behaviour on
 * engine failure is always to allow or always to deny access.
 *
 * <pre>{@code
 * // Deny all requests when the engine is unavailable (fail-closed, recommended for security)
 * FallbackStrategy fallback = new StaticFallback(false);
 *
 * // Allow all requests when the engine is unavailable (fail-open, use with caution)
 * FallbackStrategy fallback = new StaticFallback(true);
 * }</pre>
 *
 * @param result the boolean value to return as
 *               {@link EntitlementsResult#result()} when the fallback is triggered;
 *               {@code true} means allow, {@code false} means deny
 * @since 0.1.0
 */
public record StaticFallback(boolean result) implements FallbackStrategy {
}
