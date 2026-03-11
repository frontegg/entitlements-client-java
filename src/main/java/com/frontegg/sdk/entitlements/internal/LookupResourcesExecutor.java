package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.LookupResourcesRequest;
import com.authzed.api.v1.LookupResourcesResponse;

import java.util.Iterator;

/**
 * Package-private functional interface that abstracts the {@code LookupResources} gRPC
 * call, enabling query strategies to be tested without requiring a real gRPC channel.
 *
 * <p>The production implementation wraps the blocking stub with a deadline applied via
 * {@link com.authzed.api.v1.PermissionsServiceGrpc.PermissionsServiceBlockingStub#withDeadline}.
 * Test implementations can return canned iterators directly.
 */
@FunctionalInterface
interface LookupResourcesExecutor {

    /**
     * Executes a {@code LookupResources} call with the supplied request.
     *
     * @param request the lookup request; must not be {@code null}
     * @return an iterator over the responses from the authorization engine; never {@code null}
     */
    Iterator<LookupResourcesResponse> execute(LookupResourcesRequest request);
}
