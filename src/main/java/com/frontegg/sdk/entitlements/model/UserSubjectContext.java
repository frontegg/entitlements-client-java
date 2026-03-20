package com.frontegg.sdk.entitlements.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies a Frontegg user within a specific tenant as the subject of an entitlement check.
 *
 * <p>The optional {@code attributes} map carries arbitrary key-value pairs (e.g. user roles,
 * subscription tier) that can be evaluated by caveat conditions in the authorization engine.
 *
 * <p>The optional {@code permissions} list carries the permission keys the user holds (e.g.
 * {@code "fe.billing.read"}, {@code "fe.users.*"}). When present it enables client-side
 * short-circuit evaluation in {@code PermissionSpiceDBQuery}: if the requested permission does
 * not match any entry in this list the SDK returns denied without a network call. Entries
 * support simple glob wildcards — {@code *} matches one or more characters and {@code .} is
 * treated as a literal dot.
 *
 * <p>This record is immutable. The {@code attributes} map and {@code permissions} list are
 * defensively copied during construction and exposed as unmodifiable views.
 *
 * <pre>{@code
 * // With attributes (e.g. for caveat evaluation)
 * SubjectContext subject = new UserSubjectContext(
 *         "user-123",
 *         "tenant-456",
 *         Map.of("plan", "enterprise", "active_at", Instant.now().toString())
 * );
 *
 * // With permissions cache (enables client-side short-circuit)
 * SubjectContext subject = new UserSubjectContext(
 *         "user-123",
 *         "tenant-456",
 *         List.of("fe.billing.*", "fe.users.read"),
 *         Map.of("plan", "enterprise")
 * );
 *
 * // Without attributes (convenience constructor)
 * SubjectContext subject = new UserSubjectContext("user-123", "tenant-456");
 * }</pre>
 *
 * @param userId      the unique identifier of the user; must not be null or blank
 * @param tenantId    the unique identifier of the tenant the user belongs to; must not be null
 *                    or blank
 * @param permissions optional list of permission key patterns the user holds; {@code null} or
 *                    empty disables client-side permission caching
 * @param attributes  arbitrary attributes attached to the user, evaluated in caveat conditions;
 *                    never {@code null} (empty map when not provided)
 * @since 0.1.0
 */
public record UserSubjectContext(
        String userId,
        String tenantId,
        List<String> permissions,
        Map<String, Object> attributes
) implements SubjectContext {

    /**
     * Compact canonical constructor — validates required fields and makes defensive
     * unmodifiable copies of the {@code permissions} list and {@code attributes} map.
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
        permissions = permissions == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(permissions));
        attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /**
     * Convenience constructor for the common case where no permissions cache or attributes
     * are needed.
     *
     * @param userId   the unique identifier of the user; must not be null or blank
     * @param tenantId the unique identifier of the tenant; must not be null or blank
     */
    public UserSubjectContext(String userId, String tenantId) {
        this(userId, tenantId, null, Map.of());
    }

    /**
     * Convenience constructor for supplying attributes without a permissions cache.
     *
     * @param userId     the unique identifier of the user; must not be null or blank
     * @param tenantId   the unique identifier of the tenant; must not be null or blank
     * @param attributes arbitrary caveat attributes; must not be null
     */
    public UserSubjectContext(String userId, String tenantId, Map<String, Object> attributes) {
        this(userId, tenantId, null, attributes);
    }
}
