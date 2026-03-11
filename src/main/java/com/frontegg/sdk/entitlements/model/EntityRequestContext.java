package com.frontegg.sdk.entitlements.model;

import java.util.Objects;

/**
 * Requests a fine-grained authorization (FGA) check between a subject entity and a resource
 * entity via a named relation.
 *
 * <p>The authorization engine checks whether the subject has the specified {@code relation} on
 * the resource identified by {@code resourceType} and {@code resourceId}.
 *
 * <pre>{@code
 * // Check whether the current entity can "view" a specific document
 * RequestContext request = new EntityRequestContext("document", "doc-789", "viewer");
 * }</pre>
 *
 * @param resourceType the type name of the resource as registered in the SpiceDB schema (e.g.
 *                     {@code "document"}, {@code "project"}); must not be null or blank
 * @param resourceId   the unique identifier of the resource instance; must not be null or blank
 * @param relation     the relation to check (e.g. {@code "viewer"}, {@code "editor"});
 *                     must not be null or blank
 * @since 0.1.0
 */
public record EntityRequestContext(
        String resourceType,
        String resourceId,
        String relation
) implements RequestContext {

    /**
     * Compact canonical constructor — validates that no field is null or blank.
     *
     * @throws NullPointerException     if any field is {@code null}
     * @throws IllegalArgumentException if any field is blank
     */
    public EntityRequestContext {
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(relation, "relation must not be null");
        if (resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        if (resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        if (relation.isBlank()) {
            throw new IllegalArgumentException("relation must not be blank");
        }
    }
}
