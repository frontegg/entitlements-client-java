package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.ReadRelationshipsRequest;
import com.authzed.api.v1.ReadRelationshipsResponse;

import java.util.List;

/**
 * Package-private functional interface that abstracts the {@code ReadRelationships} gRPC call,
 * enabling query strategies to be tested without requiring a real gRPC channel.
 *
 * <p>The production implementation iterates the blocking stub's streaming response and collects
 * all {@link ReadRelationshipsResponse} items into a list. Test implementations can return
 * canned lists directly.
 */
@FunctionalInterface
interface ReadRelationshipsExecutor {

    /**
     * Executes a {@code ReadRelationships} call with the supplied request and returns all
     * response items as a list.
     *
     * @param request the read relationships request; must not be {@code null}
     * @return a list of responses from the authorization engine; never {@code null}
     */
    List<ReadRelationshipsResponse> execute(ReadRelationshipsRequest request);
}
