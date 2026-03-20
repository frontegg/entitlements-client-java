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
 * <p>The optional {@code limit} parameter controls the maximum number of resources returned per
 * page. When {@code null} the server-side default of 50 is applied. The optional {@code cursor}
 * parameter is an opaque token returned by a previous call; pass it to retrieve the next page.
 * A {@code null} cursor means "start from the first page".
 *
 * @param subjectType  the type of the subject (e.g. {@code "frontegg_user"})
 * @param subjectId    the subject's identifier
 * @param permission   the permission/relation to check (e.g. {@code "entitled"}, {@code "viewer"})
 * @param resourceType the type of resources to look up (e.g. {@code "frontegg_feature"})
 * @param at           the point in time at which to evaluate entitlement; {@code null} means
 *                     "check at the current time"
 * @param limit        maximum number of resources per page; {@code null} means use the default (50)
 * @param cursor       opaque pagination token from a previous response; {@code null} means first page
 * @since 0.2.0
 */
public record LookupResourcesRequest(
        String subjectType,
        String subjectId,
        String permission,
        String resourceType,
        Instant at,
        Integer limit,
        String cursor
) {
    /**
     * Compact canonical constructor — validates that required fields are not null.
     * {@code at}, {@code limit}, and {@code cursor} are nullable.
     *
     * @throws NullPointerException if any required field is {@code null}
     */
    public LookupResourcesRequest {
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        if (subjectType.isBlank()) throw new IllegalArgumentException("subjectType must not be blank");
        if (subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        if (permission.isBlank()) throw new IllegalArgumentException("permission must not be blank");
        if (resourceType.isBlank()) throw new IllegalArgumentException("resourceType must not be blank");
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        // at, limit, and cursor are nullable
    }

    /**
     * Convenience constructor for looking up resources at a specific time without pagination.
     *
     * @param subjectType  the type of the subject
     * @param subjectId    the subject's identifier
     * @param permission   the permission/relation to check
     * @param resourceType the type of resources to look up
     * @param at           the point in time at which to evaluate entitlement; {@code null} means now
     */
    public LookupResourcesRequest(String subjectType, String subjectId,
                                   String permission, String resourceType, Instant at) {
        this(subjectType, subjectId, permission, resourceType, at, null, null);
    }

    /**
     * Convenience constructor for looking up resources at the current time without pagination.
     *
     * @param subjectType  the type of the subject
     * @param subjectId    the subject's identifier
     * @param permission   the permission/relation to check
     * @param resourceType the type of resources to look up
     */
    public LookupResourcesRequest(String subjectType, String subjectId,
                                   String permission, String resourceType) {
        this(subjectType, subjectId, permission, resourceType, null, null, null);
    }
}
