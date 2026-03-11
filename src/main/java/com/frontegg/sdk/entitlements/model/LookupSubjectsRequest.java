package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Request to find all subjects of a given type that have a permission on a resource.
 *
 * <p>Maps to the SpiceDB {@code LookupSubjects} RPC. Given a resource (identified by type and
 * id) and a permission name, returns all subjects of the specified type that hold that
 * permission on the resource.
 *
 * @param resourceType the type of the resource (e.g. {@code "document"})
 * @param resourceId   the resource's identifier
 * @param permission   the permission/relation to check (e.g. {@code "viewer"})
 * @param subjectType  the type of subjects to look up (e.g. {@code "frontegg_user"})
 * @since 0.2.0
 */
public record LookupSubjectsRequest(
        String resourceType,
        String resourceId,
        String permission,
        String subjectType
) {
    /**
     * Compact canonical constructor — validates that no field is null.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public LookupSubjectsRequest {
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(subjectType, "subjectType must not be null");
    }
}
