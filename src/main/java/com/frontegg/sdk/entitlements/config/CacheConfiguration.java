package com.frontegg.sdk.entitlements.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the optional in-memory result cache.
 *
 * <p>When caching is enabled the SDK stores entitlement results in an LRU-bounded cache keyed
 * by (subject, request) pair. Cache entries expire after {@code expireAfterWrite} regardless of
 * access frequency.
 *
 * <p><strong>Phase note:</strong> Cache support is fully implemented in Phase 2. This record is
 * defined here so that the public API is stable from the outset.
 *
 * <pre>{@code
 * // Use built-in defaults (10 000 entries, 60-second TTL)
 * CacheConfiguration cache = CacheConfiguration.defaults();
 *
 * // Custom configuration
 * CacheConfiguration cache = new CacheConfiguration(5_000, Duration.ofSeconds(30));
 * }</pre>
 *
 * @param maxSize          the maximum number of entries to keep in the cache; must be &gt; 0
 * @param expireAfterWrite the time-to-live for each entry after it is written; must not be
 *                         null and must be positive
 * @since 0.1.0
 */
public record CacheConfiguration(int maxSize, Duration expireAfterWrite) {

    /**
     * Compact canonical constructor — validates field constraints.
     *
     * @throws NullPointerException     if {@code expireAfterWrite} is {@code null}
     * @throws IllegalArgumentException if {@code maxSize} is &lt;= 0 or
     *                                  {@code expireAfterWrite} is not positive
     */
    public CacheConfiguration {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        Objects.requireNonNull(expireAfterWrite, "expireAfterWrite must not be null");
        if (expireAfterWrite.isNegative() || expireAfterWrite.isZero()) {
            throw new IllegalArgumentException("expireAfterWrite must be positive");
        }
    }

    /**
     * Returns a {@code CacheConfiguration} with sensible defaults: a maximum of 10 000 entries
     * and a 60-second write TTL.
     *
     * @return a default {@code CacheConfiguration}
     * @since 0.1.0
     */
    public static CacheConfiguration defaults() {
        return new CacheConfiguration(10_000, Duration.ofSeconds(60));
    }
}
