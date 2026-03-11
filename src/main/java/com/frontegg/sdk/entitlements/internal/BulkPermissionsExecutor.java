package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsResponse;

/**
 * Package-private functional interface that abstracts the {@code CheckBulkPermissions} gRPC
 * call, enabling query strategies to be tested without requiring a real gRPC channel.
 *
 * <p>The production implementation wraps the blocking stub with a deadline applied via
 * {@link com.authzed.api.v1.PermissionsServiceGrpc.PermissionsServiceBlockingStub#withDeadline}.
 * Test implementations can return canned responses directly.
 */
@FunctionalInterface
interface BulkPermissionsExecutor {

    /**
     * Executes a {@code CheckBulkPermissions} call with the supplied request.
     *
     * @param request the bulk check request; must not be {@code null}
     * @return the response from the authorization engine; never {@code null}
     */
    CheckBulkPermissionsResponse execute(CheckBulkPermissionsRequest request);
}
