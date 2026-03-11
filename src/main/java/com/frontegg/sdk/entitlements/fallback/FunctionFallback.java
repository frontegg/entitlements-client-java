package com.frontegg.sdk.entitlements.fallback;

import com.frontegg.sdk.entitlements.model.EntitlementsResult;

import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link FallbackStrategy} that delegates the fallback decision to a caller-supplied
 * {@link Function}.
 *
 * <p>The handler receives a {@link FallbackContext} that contains the original
 * {@link com.frontegg.sdk.entitlements.model.SubjectContext},
 * {@link com.frontegg.sdk.entitlements.model.RequestContext}, and the root-cause
 * {@link Throwable}. This enables fine-grained fallback logic that can vary the result based
 * on who is being checked, what is being checked, or what type of error occurred.
 *
 * <pre>{@code
 * FallbackStrategy fallback = new FunctionFallback(ctx -> {
 *     // Allow monitoring users even when the engine is down
 *     if (ctx.requestContext() instanceof FeatureRequestContext f
 *             && "monitoring-dashboard".equals(f.featureKey())) {
 *         return EntitlementsResult.allowed();
 *     }
 *     return EntitlementsResult.denied();
 * });
 * }</pre>
 *
 * @param handler the function invoked with the {@link FallbackContext} when a fallback is
 *                triggered; must not be {@code null}
 * @since 0.1.0
 */
public record FunctionFallback(
        Function<FallbackContext, EntitlementsResult> handler
) implements FallbackStrategy {

    /**
     * Compact canonical constructor — validates that {@code handler} is not null.
     *
     * @throws NullPointerException if {@code handler} is {@code null}
     */
    public FunctionFallback {
        Objects.requireNonNull(handler, "handler must not be null");
    }
}
