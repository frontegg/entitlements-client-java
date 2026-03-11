package com.frontegg.sdk.entitlements.test;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * A configurable mock implementation of {@link EntitlementsClient} for use in tests.
 *
 * <p>By default, all entitlement checks return {@link EntitlementsResult#denied()}.
 * Use {@link #setDefaultResult(EntitlementsResult)} to change the default,
 * or {@link #setHandler(BiFunction)} for custom logic.
 *
 * <pre>{@code
 * MockEntitlementsClient mock = new MockEntitlementsClient();
 * mock.setDefaultResult(EntitlementsResult.allowed());
 *
 * // Use in your tests
 * EntitlementsResult result = mock.isEntitledTo(subject, request);
 * assertTrue(result.result());
 * }</pre>
 *
 * @since 0.2.0
 */
public class MockEntitlementsClient implements EntitlementsClient {

    private volatile EntitlementsResult defaultResult = EntitlementsResult.denied();
    private volatile BiFunction<SubjectContext, RequestContext, EntitlementsResult> handler;
    private volatile LookupResult defaultLookupResourcesResult = LookupResult.empty();
    private volatile LookupResult defaultLookupSubjectsResult = LookupResult.empty();
    private volatile boolean closed = false;

    /**
     * Sets the default result returned by {@link #isEntitledTo}.
     */
    public void setDefaultResult(EntitlementsResult result) {
        this.defaultResult = Objects.requireNonNull(result);
    }

    /**
     * Sets a custom handler function for entitlement checks.
     * When set, this takes precedence over the default result.
     */
    public void setHandler(BiFunction<SubjectContext, RequestContext, EntitlementsResult> handler) {
        this.handler = handler;
    }

    /**
     * Sets the default result for lookupResources calls.
     */
    public void setDefaultLookupResourcesResult(LookupResult result) {
        this.defaultLookupResourcesResult = Objects.requireNonNull(result);
    }

    /**
     * Sets the default result for lookupSubjects calls.
     */
    public void setDefaultLookupSubjectsResult(LookupResult result) {
        this.defaultLookupSubjectsResult = Objects.requireNonNull(result);
    }

    @Override
    public EntitlementsResult isEntitledTo(SubjectContext subjectContext, RequestContext requestContext) {
        checkNotClosed();
        if (handler != null) {
            return handler.apply(subjectContext, requestContext);
        }
        return defaultResult;
    }

    @Override
    public CompletableFuture<EntitlementsResult> isEntitledToAsync(SubjectContext subjectContext,
                                                                    RequestContext requestContext) {
        checkNotClosed();
        return CompletableFuture.completedFuture(isEntitledTo(subjectContext, requestContext));
    }

    @Override
    public LookupResult lookupResources(LookupResourcesRequest request) {
        checkNotClosed();
        return defaultLookupResourcesResult;
    }

    @Override
    public CompletableFuture<LookupResult> lookupResourcesAsync(LookupResourcesRequest request) {
        checkNotClosed();
        return CompletableFuture.completedFuture(lookupResources(request));
    }

    @Override
    public LookupResult lookupSubjects(LookupSubjectsRequest request) {
        checkNotClosed();
        return defaultLookupSubjectsResult;
    }

    @Override
    public CompletableFuture<LookupResult> lookupSubjectsAsync(LookupSubjectsRequest request) {
        checkNotClosed();
        return CompletableFuture.completedFuture(lookupSubjects(request));
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * Returns {@code true} if {@link #close()} has been called on this mock.
     */
    public boolean isClosed() {
        return closed;
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Mock client has been closed");
        }
    }
}
