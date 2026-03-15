package com.frontegg.sdk.entitlements.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Request to find all subjects of a given type that have a permission on a resource.
 *
 * <p>Maps to the SpiceDB {@code LookupSubjects} RPC. Given a resource (identified by type and
 * id) and a permission name, returns all subjects of the specified type that hold that
 * permission on the resource.
 *
 * <p>The optional {@code at} parameter allows looking up subjects at a specific point in time
 * (past or future). When {@code at} is non-null it is forwarded to the authorization engine as
 * an {@code "at"} field in the caveat context using ISO-8601 string format. A {@code null}
 * value means "check at the current time".
 *
 * @param resourceType the type of the resource (e.g. {@code "document"})
 * @param resourceId   the resource's identifier
 * @param permission   the permission/relation to check (e.g. {@code "viewer"})
 * @param subjectType  the type of subjects to look up (e.g. {@code "frontegg_user"})
 * @param at           the point in time at which to evaluate entitlement; {@code null} means
 *                     "check at the current time"
 * @since 0.2.0
 */
public record LookupSubjectsRequest(
        String resourceType,
        String resourceId,
        String permission,
        String subjectType,
        Instant at
) {
    /**
     * Compact canonical constructor — validates that required fields are not null.
     * {@code at} is nullable; {@code null} means "check at the current time".
     *
     * @throws NullPointerException if any required field is {@code null}
     */
    public LookupSubjectsRequest {
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        // at is nullable — null means "check at current time"
    }

    /**
     * Convenience constructor for looking up subjects at the current time.
     *
     * @param resourceType the type of the resource
     * @param resourceId   the resource's identifier
     * @param permission   the permission/relation to check
     * @param subjectType  the type of subjects to look up
     */
    public LookupSubjectsRequest(String resourceType, String resourceId,
                                  String permission, String subjectType) {
        this(resourceType, resourceId, permission, subjectType, null);
    }
}
