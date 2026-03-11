package com.frontegg.sdk.entitlements.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies a Frontegg user within a specific tenant as the subject of an entitlement check.
 *
 * <p>The optional {@code attributes} map carries arbitrary key-value pairs (e.g. user roles,
 * subscription tier) that can be evaluated by caveat conditions in the authorization engine.
 *
 * <p>This record is immutable. The {@code attributes} map is defensively copied during
 * construction and exposed as an unmodifiable view.
 *
 * <pre>{@code
 * // With attributes (e.g. for caveat evaluation)
 * SubjectContext subject = new UserSubjectContext(
 *         "user-123",
 *         "tenant-456",
 *         Map.of("plan", "enterprise", "active_at", Instant.now().toString())
 * );
 *
 * // Without attributes (convenience constructor)
 * SubjectContext subject = new UserSubjectContext("user-123", "tenant-456");
 * }</pre>
 *
 * @param userId     the unique identifier of the user; must not be null or blank
 * @param tenantId   the unique identifier of the tenant the user belongs to; must not be null
 *                   or blank
 * @param attributes arbitrary attributes attached to the user, evaluated in caveat conditions;
 *                   never {@code null} (empty map when not provided)
 * @since 0.1.0
 */
public record UserSubjectContext(
        String userId,
        String tenantId,
        Map<String, Object> attributes
) implements SubjectContext {

    /**
     * Compact canonical constructor — validates required fields and makes a defensive
     * unmodifiable copy of the {@code attributes} map.
     *
     * @throws NullPointerException     if {@code userId}, {@code tenantId}, or
     *                                  {@code attributes} is {@code null}
     * @throws IllegalArgumentException if {@code userId} or {@code tenantId} is blank
     */
    public UserSubjectContext {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /**
     * Convenience constructor for the common case where no user attributes are needed.
     *
     * @param userId   the unique identifier of the user; must not be null or blank
     * @param tenantId the unique identifier of the tenant; must not be null or blank
     */
    public UserSubjectContext(String userId, String tenantId) {
        this(userId, tenantId, Map.of());
    }
}
