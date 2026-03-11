package com.frontegg.sdk.entitlements.cache;

import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link CacheProvider} implementation backed by
 * <a href="https://github.com/ben-manes/caffeine">Caffeine</a>.
 *
 * <p>This is the default cache implementation provided by the SDK. It requires
 * {@code com.github.ben-manes.caffeine:caffeine} on the classpath. The dependency is declared
 * {@code optional} in the SDK artifact, so consumers must add it explicitly if they intend to
 * use this class:
 *
 * <pre>{@code
 * // Maven
 * <dependency>
 *     <groupId>com.github.ben-manes.caffeine</groupId>
 *     <artifactId>caffeine</artifactId>
 *     <version>3.1.8</version>
 * </dependency>
 * }</pre>
 *
 * <p>The cache is size-bounded (via {@link CacheConfiguration#maxSize()}) and uses a
 * write-time TTL (via {@link CacheConfiguration#expireAfterWrite()}). All operations are
 * thread-safe — Caffeine's internal implementation uses concurrent data structures throughout.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * CacheConfiguration cacheConfig = CacheConfiguration.defaults();
 * CaffeineCacheProvider<EntitlementsCacheKey, EntitlementsResult> cache =
 *         new CaffeineCacheProvider<>(cacheConfig);
 * }</pre>
 *
 * @param <K> the type of cache keys; must implement {@link Object#equals(Object)} and
 *            {@link Object#hashCode()} correctly
 * @param <V> the type of cached values
 * @since 0.2.0
 */
public final class CaffeineCacheProvider<K, V> implements CacheProvider<K, V> {

    private final Cache<K, V> cache;

    /**
     * Constructs a new {@code CaffeineCacheProvider} using the supplied configuration.
     *
     * <p>The underlying Caffeine cache is configured with:
     * <ul>
     *   <li>{@link CacheConfiguration#maxSize()} as the maximum number of entries</li>
     *   <li>{@link CacheConfiguration#expireAfterWrite()} as the write-time TTL</li>
     * </ul>
     *
     * @param config the cache configuration; must not be {@code null}
     * @throws NullPointerException if {@code config} is {@code null}
     * @since 0.2.0
     */
    public CaffeineCacheProvider(CacheConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.maxSize())
                .expireAfterWrite(config.expireAfterWrite())
                .build();
    }

    /**
     * Returns the cached value for the given key, or an empty {@link Optional} if no entry
     * exists or the entry has expired.
     *
     * @param key the cache key; must not be {@code null}
     * @return an {@link Optional} containing the cached value, or {@link Optional#empty()} if
     *         not present or expired
     * @since 0.2.0
     */
    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    /**
     * Associates the given value with the given key in the cache. If the cache already contains
     * an entry for the key, the value is replaced.
     *
     * @param key   the cache key; must not be {@code null}
     * @param value the value to cache; must not be {@code null}
     * @since 0.2.0
     */
    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    /**
     * Removes the cache entry for the given key, if present. This is a no-op if the key is
     * not in the cache.
     *
     * @param key the cache key; must not be {@code null}
     * @since 0.2.0
     */
    @Override
    public void invalidate(K key) {
        cache.invalidate(key);
    }

    /**
     * Removes all entries from the cache.
     *
     * @since 0.2.0
     */
    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
