package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Request to find all resources of a given type that a subject has access to.
 *
 * <p>Maps to the SpiceDB {@code LookupResources} RPC. Given a subject (identified by type and
 * id) and a permission name, returns all resources of the specified type on which the subject
 * holds that permission.
 *
 * @param subjectType  the type of the subject (e.g. {@code "frontegg_user"})
 * @param subjectId    the subject's identifier
 * @param permission   the permission/relation to check (e.g. {@code "entitled"}, {@code "viewer"})
 * @param resourceType the type of resources to look up (e.g. {@code "frontegg_feature"})
 * @since 0.2.0
 */
public record LookupResourcesRequest(
        String subjectType,
        String subjectId,
        String permission,
        String resourceType
) {
    /**
     * Compact canonical constructor — validates that no field is null.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public LookupResourcesRequest {
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
    }
}
