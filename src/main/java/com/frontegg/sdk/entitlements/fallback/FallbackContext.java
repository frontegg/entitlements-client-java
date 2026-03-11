package com.frontegg.sdk.entitlements.fallback;

import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;

import java.util.Objects;

/**
 * Contextual information passed to a {@link FunctionFallback} handler when the authorization
 * engine fails after all retry attempts are exhausted.
 *
 * <p>The context contains the original subject and request that triggered the check, plus the
 * root-cause throwable that caused the failure. Handlers can inspect these fields to apply
 * context-sensitive fallback logic.
 *
 * <pre>{@code
 * FallbackStrategy fallback = new FunctionFallback(ctx -> {
 *     logger.warn("Entitlement check failed for subject={}, error={}",
 *             ctx.subjectContext(), ctx.error().getMessage());
 *     return EntitlementsResult.denied();
 * });
 * }</pre>
 *
 * @param subjectContext the subject that was being checked when the failure occurred;
 *                       never {@code null}
 * @param requestContext the resource or permission that was being checked when the failure
 *                       occurred; never {@code null}
 * @param error          the root-cause exception that triggered the fallback; never {@code null}
 * @since 0.1.0
 */
public record FallbackContext(
        SubjectContext subjectContext,
        RequestContext requestContext,
        Throwable error
) {

    /**
     * Compact canonical constructor — validates that no field is null.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public FallbackContext {
        Objects.requireNonNull(subjectContext, "subjectContext must not be null");
        Objects.requireNonNull(requestContext, "requestContext must not be null");
        Objects.requireNonNull(error, "error must not be null");
    }
}
