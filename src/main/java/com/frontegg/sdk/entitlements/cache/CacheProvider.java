package com.frontegg.sdk.entitlements.cache;

import java.util.Optional;

/**
 * Service-provider interface for pluggable cache implementations used by the
 * {@link com.frontegg.sdk.entitlements.EntitlementsClient}.
 *
 * <p>All operations must be thread-safe. Implementations are free to apply any eviction policy
 * (LRU, TTL-based, size-bounded, etc.).
 *
 * <p><strong>Phase note:</strong> Cache support is fully wired in Phase 2. In Phase 1 this
 * interface is defined so that consumers and future SDK modules can supply custom
 * implementations without breaking changes.
 *
 * <p>Example implementation using a {@link java.util.concurrent.ConcurrentHashMap}:
 *
 * <pre>{@code
 * public class MapCacheProvider<K, V> implements CacheProvider<K, V> {
 *     private final Map<K, V> store = new ConcurrentHashMap<>();
 *
 *     public Optional<V> get(K key)        { return Optional.ofNullable(store.get(key)); }
 *     public void put(K key, V value)      { store.put(key, value); }
 *     public void invalidate(K key)        { store.remove(key); }
 *     public void invalidateAll()          { store.clear(); }
 * }
 * }</pre>
 *
 * @param <K> the type of cache keys
 * @param <V> the type of cached values
 * @since 0.1.0
 */
public interface CacheProvider<K, V> {

    /**
     * Returns the cached value for the given key, or an empty {@link Optional} if no entry
     * exists (including if the entry has expired).
     *
     * @param key the cache key; must not be {@code null}
     * @return an {@link Optional} containing the cached value, or {@link Optional#empty()} if
     *         not present
     * @since 0.1.0
     */
    Optional<V> get(K key);

    /**
     * Associates the given value with the given key in the cache. If the cache already contains
     * an entry for the key, the value is replaced.
     *
     * @param key   the cache key; must not be {@code null}
     * @param value the value to cache; must not be {@code null}
     * @since 0.1.0
     */
    void put(K key, V value);

    /**
     * Removes the cache entry for the given key, if present. This is a no-op if the key is
     * not in the cache.
     *
     * @param key the cache key; must not be {@code null}
     * @since 0.1.0
     */
    void invalidate(K key);

    /**
     * Removes all entries from the cache.
     *
     * @since 0.1.0
     */
    void invalidateAll();
}
