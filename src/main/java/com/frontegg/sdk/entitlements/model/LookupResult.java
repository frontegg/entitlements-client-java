package com.frontegg.sdk.entitlements.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of a lookup operation, containing the list of matching entity IDs.
 *
 * <p>The returned list is always non-null and immutable. Entity IDs are returned in the
 * order they were received from the authorization engine; ordering is not guaranteed to
 * be stable across calls.
 *
 * @param entityIds the list of entity IDs that matched the lookup; never {@code null}, may be empty
 * @since 0.2.0
 */
public record LookupResult(List<String> entityIds) {

    /**
     * Compact canonical constructor — validates that the list is not null and makes a
     * defensive, unmodifiable copy.
     *
     * @throws NullPointerException if {@code entityIds} is {@code null}
     */
    public LookupResult {
        Objects.requireNonNull(entityIds, "entityIds must not be null");
        entityIds = Collections.unmodifiableList(List.copyOf(entityIds));
    }

    /**
     * Convenience factory that returns an empty {@link LookupResult}.
     *
     * @return a {@code LookupResult} with no entity IDs
     */
    public static LookupResult empty() {
        return new LookupResult(List.of());
    }
}
