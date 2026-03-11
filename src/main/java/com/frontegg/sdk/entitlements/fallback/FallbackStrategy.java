package com.frontegg.sdk.entitlements.fallback;

/**
 * Strategy that determines what {@link com.frontegg.sdk.entitlements.model.EntitlementsResult}
 * to return when the authorization engine is unreachable or returns an error after all retry
 * attempts are exhausted.
 *
 * <p>{@code FallbackStrategy} is a sealed interface; all permitted implementations are defined
 * in this package. Callers can exhaustively switch over all cases using a Java 17+
 * pattern-matching {@code switch} expression:
 *
 * <pre>{@code
 * switch (fallbackStrategy) {
 *     case StaticFallback   s -> applyStatic(s);
 *     case FunctionFallback f -> applyFunction(f, context);
 * }
 * }</pre>
 *
 * <p>Permitted implementations:
 * <ul>
 *   <li>{@link StaticFallback} — always returns a fixed boolean result</li>
 *   <li>{@link FunctionFallback} — delegates to a caller-supplied function that can inspect
 *       the {@link FallbackContext} (subject, request, and root cause) to compute a result</li>
 * </ul>
 *
 * <p>When no fallback strategy is configured on
 * {@link com.frontegg.sdk.entitlements.config.ClientConfiguration}, exceptions propagate
 * directly to the caller.
 *
 * @since 0.1.0
 */
public sealed interface FallbackStrategy permits StaticFallback, FunctionFallback {
}
