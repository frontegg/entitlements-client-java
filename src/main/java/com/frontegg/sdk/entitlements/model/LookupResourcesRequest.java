package com.frontegg.sdk.entitlements.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Request to find all resources of a given type that a subject has access to.
 *
 * <p>Maps to the SpiceDB {@code LookupResources} RPC. Given a subject (identified by type and
 * id) and a permission name, returns all resources of the specified type on which the subject
 * holds that permission.
 *
 * <p>The optional {@code at} parameter allows looking up resources at a specific point in time
 * (past or future). When {@code at} is non-null it is forwarded to the authorization engine as
 * an {@code "at"} field in the caveat context using ISO-8601 string format. A {@code null}
 * value means "check at the current time".
 *
 * @param subjectType  the type of the subject (e.g. {@code "frontegg_user"})
 * @param subjectId    the subject's identifier
 * @param permission   the permission/relation to check (e.g. {@code "entitled"}, {@code "viewer"})
 * @param resourceType the type of resources to look up (e.g. {@code "frontegg_feature"})
 * @param at           the point in time at which to evaluate entitlement; {@code null} means
 *                     "check at the current time"
 * @since 0.2.0
 */
public record LookupResourcesRequest(
        String subjectType,
        String subjectId,
        String permission,
        String resourceType,
        Instant at
) {
    /**
     * Compact canonical constructor — validates that required fields are not null.
     * {@code at} is nullable; {@code null} means "check at the current time".
     *
     * @throws NullPointerException if any required field is {@code null}
     */
    public LookupResourcesRequest {
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        // at is nullable — null means "check at current time"
    }

    /**
     * Convenience constructor for looking up resources at the current time.
     *
     * @param subjectType  the type of the subject
     * @param subjectId    the subject's identifier
     * @param permission   the permission/relation to check
     * @param resourceType the type of resources to look up
     */
    public LookupResourcesRequest(String subjectType, String subjectId,
                                   String permission, String resourceType) {
        this(subjectType, subjectId, permission, resourceType, null);
    }
}
