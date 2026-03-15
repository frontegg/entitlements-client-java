package com.frontegg.sdk.entitlements.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Requests a fine-grained authorization (FGA) check between a subject entity and a resource
 * entity via a named relation.
 *
 * <p>The authorization engine checks whether the subject has the specified {@code relation} on
 * the resource identified by {@code resourceType} and {@code resourceId}.
 *
 * <p>The optional {@code at} parameter allows checking entitlement at a specific point in time
 * (past or future). When {@code at} is non-null it is forwarded to the authorization engine as
 * an {@code "at"} field in the caveat context using ISO-8601 string format. A {@code null}
 * value means "check at the current time".
 *
 * <pre>{@code
 * // Check whether the current entity can "view" a specific document
 * RequestContext request = new EntityRequestContext("document", "doc-789", "viewer");
 *
 * // Check at a specific point in time
 * RequestContext request = new EntityRequestContext("document", "doc-789", "viewer",
 *         Instant.parse("2026-01-01T00:00:00Z"));
 * }</pre>
 *
 * @param resourceType the type name of the resource as registered in the SpiceDB schema (e.g.
 *                     {@code "document"}, {@code "project"}); must not be null or blank
 * @param resourceId   the unique identifier of the resource instance; must not be null or blank
 * @param relation     the relation to check (e.g. {@code "viewer"}, {@code "editor"});
 *                     must not be null or blank
 * @param at           the point in time at which to evaluate entitlement; {@code null} means
 *                     "check at the current time"
 * @since 0.1.0
 */
public record EntityRequestContext(
        String resourceType,
        String resourceId,
        String relation,
        Instant at
) implements RequestContext {

    /**
     * Compact canonical constructor — validates that no field is null or blank.
     * {@code at} is nullable; {@code null} means "check at the current time".
     *
     * @throws NullPointerException     if {@code resourceType}, {@code resourceId}, or {@code relation} is {@code null}
     * @throws IllegalArgumentException if any required field is blank
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
        // at is nullable — null means "check at current time"
    }

    /**
     * Convenience constructor for checking at the current time.
     *
     * @param resourceType the resource type; must not be null or blank
     * @param resourceId   the resource id; must not be null or blank
     * @param relation     the relation to check; must not be null or blank
     */
    public EntityRequestContext(String resourceType, String resourceId, String relation) {
        this(resourceType, resourceId, relation, null);
    }
}
