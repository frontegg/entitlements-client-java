package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.LookupSubjectsRequest;
import com.authzed.api.v1.LookupSubjectsResponse;

import java.util.Iterator;

/**
 * Package-private functional interface that abstracts the {@code LookupSubjects} gRPC
 * call, enabling query strategies to be tested without requiring a real gRPC channel.
 *
 * <p>The production implementation wraps the blocking stub with a deadline applied via
 * {@link com.authzed.api.v1.PermissionsServiceGrpc.PermissionsServiceBlockingStub#withDeadline}.
 * Test implementations can return canned iterators directly.
 */
@FunctionalInterface
interface LookupSubjectsExecutor {

    /**
     * Executes a {@code LookupSubjects} call with the supplied request.
     *
     * @param request the lookup request; must not be {@code null}
     * @return an iterator over the responses from the authorization engine; never {@code null}
     */
    Iterator<LookupSubjectsResponse> execute(LookupSubjectsRequest request);
}
