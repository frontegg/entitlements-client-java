package com.frontegg.sdk.entitlements.test;

import com.frontegg.sdk.entitlements.EntitlementsClient;
import com.frontegg.sdk.entitlements.model.EntitlementsResult;
import com.frontegg.sdk.entitlements.model.LookupResourcesRequest;
import com.frontegg.sdk.entitlements.model.LookupResult;
import com.frontegg.sdk.entitlements.model.LookupSubjectsRequest;
import com.frontegg.sdk.entitlements.model.RequestContext;
import com.frontegg.sdk.entitlements.model.SubjectContext;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A decorating {@link EntitlementsClient} that records all entitlement check invocations.
 *
 * <p>Useful for verifying that your code makes the expected entitlement checks.
 *
 * <pre>{@code
 * RecordingEntitlementsClient recorder = new RecordingEntitlementsClient(delegate);
 *
 * // ... run your code ...
 *
 * assertEquals(2, recorder.getIsEntitledToCalls().size());
 * assertEquals("feature-x", ((FeatureRequestContext) recorder.getIsEntitledToCalls().get(0).request()).featureKey());
 * }</pre>
 *
 * @since 0.2.0
 */
public class RecordingEntitlementsClient implements EntitlementsClient {

    private final EntitlementsClient delegate;
    private final List<EntitlementCall> isEntitledToCalls = new CopyOnWriteArrayList<>();
    private final List<LookupResourcesRequest> lookupResourcesCalls = new CopyOnWriteArrayList<>();
    private final List<LookupSubjectsRequest> lookupSubjectsCalls = new CopyOnWriteArrayList<>();

    /**
     * Creates a new recording client that delegates all calls to the given {@code delegate}.
     *
     * @param delegate the underlying client to forward calls to; must not be {@code null}
     */
    public RecordingEntitlementsClient(EntitlementsClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Recorded entitlement check call.
     */
    public record EntitlementCall(SubjectContext subject, RequestContext request) {}

    @Override
    public EntitlementsResult isEntitledTo(SubjectContext subjectContext, RequestContext requestContext) {
        isEntitledToCalls.add(new EntitlementCall(subjectContext, requestContext));
        return delegate.isEntitledTo(subjectContext, requestContext);
    }

    @Override
    public CompletableFuture<EntitlementsResult> isEntitledToAsync(SubjectContext subjectContext,
                                                                    RequestContext requestContext) {
        isEntitledToCalls.add(new EntitlementCall(subjectContext, requestContext));
        return delegate.isEntitledToAsync(subjectContext, requestContext);
    }

    @Override
    public LookupResult lookupResources(LookupResourcesRequest request) {
        lookupResourcesCalls.add(request);
        return delegate.lookupResources(request);
    }

    @Override
    public CompletableFuture<LookupResult> lookupResourcesAsync(LookupResourcesRequest request) {
        lookupResourcesCalls.add(request);
        return delegate.lookupResourcesAsync(request);
    }

    @Override
    public LookupResult lookupSubjects(LookupSubjectsRequest request) {
        lookupSubjectsCalls.add(request);
        return delegate.lookupSubjects(request);
    }

    @Override
    public CompletableFuture<LookupResult> lookupSubjectsAsync(LookupSubjectsRequest request) {
        lookupSubjectsCalls.add(request);
        return delegate.lookupSubjectsAsync(request);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * Returns an unmodifiable view of all recorded {@code isEntitledTo} calls in the order they
     * were made.
     */
    public List<EntitlementCall> getIsEntitledToCalls() {
        return Collections.unmodifiableList(isEntitledToCalls);
    }

    /**
     * Returns an unmodifiable view of all recorded {@code lookupResources} calls in the order
     * they were made.
     */
    public List<LookupResourcesRequest> getLookupResourcesCalls() {
        return Collections.unmodifiableList(lookupResourcesCalls);
    }

    /**
     * Returns an unmodifiable view of all recorded {@code lookupSubjects} calls in the order
     * they were made.
     */
    public List<LookupSubjectsRequest> getLookupSubjectsCalls() {
        return Collections.unmodifiableList(lookupSubjectsCalls);
    }

    /**
     * Clears all recorded calls. Useful for resetting state between test cases when reusing the
     * same recorder instance.
     */
    public void reset() {
        isEntitledToCalls.clear();
        lookupResourcesCalls.clear();
        lookupSubjectsCalls.clear();
    }
}
