package com.frontegg.sdk.entitlements.internal;

import com.authzed.api.v1.CheckPermissionRequest;
import com.authzed.api.v1.CheckPermissionResponse;

/**
 * Package-private functional interface that abstracts the {@code CheckPermission} gRPC
 * call, enabling query strategies to be tested without requiring a real gRPC channel.
 *
 * <p>The production implementation wraps the blocking stub with a deadline applied via
 * {@link com.authzed.api.v1.PermissionsServiceGrpc.PermissionsServiceBlockingStub#withDeadline}.
 * Test implementations can return canned responses directly.
 */
@FunctionalInterface
interface CheckPermissionExecutor {

    /**
     * Executes a {@code CheckPermission} call with the supplied request.
     *
     * @param request the check request; must not be {@code null}
     * @return the response from the authorization engine; never {@code null}
     */
    CheckPermissionResponse execute(CheckPermissionRequest request);
}
