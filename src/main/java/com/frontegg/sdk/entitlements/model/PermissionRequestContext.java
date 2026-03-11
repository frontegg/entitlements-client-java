package com.frontegg.sdk.entitlements.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Requests an entitlement check against one or more named permissions.
 *
 * <p>The authorization engine checks whether the subject has the {@code entitled} relation to
 * every {@code frontegg_permission} resource identified by the supplied keys. When multiple
 * keys are provided the check is treated as a logical AND — the subject must be entitled to
 * all of them.
 *
 * <p>The optional {@code at} parameter allows checking entitlement at a specific point in time
 * (past or future). When {@code at} is non-null it is forwarded to the authorization engine as
 * an {@code "at"} field in the caveat context using ISO-8601 string format. A {@code null}
 * value means "check at the current time".
 *
 * <pre>{@code
 * // Single permission (convenience constructor)
 * RequestContext request = new PermissionRequestContext("reports:read");
 *
 * // Multiple permissions
 * RequestContext request = new PermissionRequestContext(
 *         List.of("reports:read", "reports:export")
 * );
 *
 * // Check at a specific point in time
 * RequestContext request = new PermissionRequestContext(
 *         List.of("reports:read"),
 *         Instant.parse("2026-01-01T00:00:00Z")
 * );
 * }</pre>
 *
 * <p>This record is immutable. The {@code permissionKeys} list is defensively copied during
 * construction and exposed as an unmodifiable view.
 *
 * @param permissionKeys the permission keys to check; must not be null, must not be empty
 * @param at             the point in time at which to evaluate entitlement; {@code null} means
 *                       current time
 * @since 0.1.0
 */
public record PermissionRequestContext(List<String> permissionKeys, Instant at) implements RequestContext {

    /**
     * Compact canonical constructor — validates the list and makes a defensive unmodifiable copy.
     * {@code at} is nullable; {@code null} means "check at the current time".
     *
     * @throws NullPointerException     if {@code permissionKeys} is {@code null}
     * @throws IllegalArgumentException if {@code permissionKeys} is empty
     */
    public PermissionRequestContext {
        Objects.requireNonNull(permissionKeys, "permissionKeys must not be null");
        if (permissionKeys.isEmpty()) {
            throw new IllegalArgumentException("permissionKeys must not be empty");
        }
        for (String key : permissionKeys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("permissionKeys must not contain null or blank entries");
            }
        }
        permissionKeys = Collections.unmodifiableList(List.copyOf(permissionKeys));
        // at is nullable
    }

    /**
     * Convenience constructor for checking a list of permissions at the current time.
     *
     * @param permissionKeys the permission keys to check; must not be null or empty
     */
    public PermissionRequestContext(List<String> permissionKeys) {
        this(permissionKeys, null);
    }

    /**
     * Convenience constructor for checking a single permission key at the current time.
     *
     * @param permissionKey the single permission key to check; must not be null or blank
     */
    public PermissionRequestContext(String permissionKey) {
        this(List.of(permissionKey), null);
    }
}
