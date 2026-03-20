package com.frontegg.sdk.entitlements.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of a lookup operation, containing the list of matching entity IDs and an optional
 * pagination cursor for retrieving the next page.
 *
 * <p>The returned list is always non-null and immutable. Entity IDs are returned in the
 * order they were received from the authorization engine; ordering is not guaranteed to
 * be stable across calls.
 *
 * <p>When {@code nextCursor} is non-null, more results may be available. Pass the token as
 * the {@code cursor} field of a new {@link LookupResourcesRequest} to retrieve the next page.
 * A {@code null} {@code nextCursor} means the current page is the last one.
 *
 * @param entityIds  the list of entity IDs that matched the lookup; never {@code null}, may be empty
 * @param nextCursor opaque pagination token; {@code null} when no further pages exist
 * @since 0.2.0
 */
public record LookupResult(List<String> entityIds, String nextCursor) {

    /**
     * Compact canonical constructor — validates that the list is not null and makes a
     * defensive, unmodifiable copy. {@code nextCursor} is nullable.
     *
     * @throws NullPointerException if {@code entityIds} is {@code null}
     */
    public LookupResult {
        Objects.requireNonNull(entityIds, "entityIds must not be null");
        entityIds = Collections.unmodifiableList(List.copyOf(entityIds));
        // nextCursor is nullable — null means no further pages
    }

    /**
     * Convenience constructor for results without a pagination cursor (single-page or last page).
     *
     * @param entityIds the list of entity IDs that matched the lookup; must not be {@code null}
     */
    public LookupResult(List<String> entityIds) {
        this(entityIds, null);
    }

    /**
     * Convenience factory that returns an empty {@link LookupResult}.
     *
     * @return a {@code LookupResult} with no entity IDs and no next cursor
     */
    public static LookupResult empty() {
        return new LookupResult(List.of());
    }
}
