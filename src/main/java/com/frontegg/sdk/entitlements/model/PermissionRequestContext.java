package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Requests an entitlement check against a named permission.
 *
 * <p>The authorization engine checks whether the subject has the {@code entitled} relation to
 * the {@code frontegg_permission} resource identified by the supplied key.
 *
 * <pre>{@code
 * RequestContext request = new PermissionRequestContext("reports:read");
 * }</pre>
 *
 * <p>This record is immutable.
 *
 * @param permissionKey the permission key to check; must not be null or blank
 * @since 0.1.0
 */
public record PermissionRequestContext(String permissionKey) implements RequestContext {

    /**
     * Compact canonical constructor — validates that {@code permissionKey} is not null or blank.
     *
     * @throws NullPointerException     if {@code permissionKey} is {@code null}
     * @throws IllegalArgumentException if {@code permissionKey} is blank
     */
    public PermissionRequestContext {
        Objects.requireNonNull(permissionKey, "permissionKey must not be null");
        if (permissionKey.isBlank()) {
            throw new IllegalArgumentException("permissionKey must not be blank");
        }
    }
}
