package com.frontegg.sdk.entitlements.cache;

import com.frontegg.sdk.entitlements.config.CacheConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CaffeineCacheProvider}.
 *
 * <p>TTL-based expiry is tested with a 100 ms TTL to keep test execution fast while avoiding
 * false negatives. If timing-sensitive tests prove flaky in CI they can be tagged
 * {@code @Tag("timing-sensitive")} and excluded with a Maven Surefire exclusion rule.
 */
class CaffeineCacheProviderTest {

    private CaffeineCacheProvider<String, String> cache;

    @BeforeEach
    void setUp() {
        CacheConfiguration config = new CacheConfiguration(100, Duration.ofMinutes(5));
        cache = new CaffeineCacheProvider<>(config);
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void constructor_nullConfig_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> new CaffeineCacheProvider<String, String>(null),
                "Null config must throw NullPointerException");
    }

    // -------------------------------------------------------------------------
    // get
    // -------------------------------------------------------------------------

    @Test
    void get_missingKey_returnsEmpty() {
        Optional<String> result = cache.get("nonexistent-key");
        assertFalse(result.isPresent(), "get() for a missing key must return Optional.empty()");
    }

    @Test
    void get_afterPut_returnsValue() {
        cache.put("key1", "value1");
        Optional<String> result = cache.get("key1");
        assertTrue(result.isPresent(), "get() must return a non-empty Optional after put()");
        assertEquals("value1", result.get(), "get() must return the value stored by put()");
    }

    @Test
    void get_afterPut_differentKey_returnsEmpty() {
        cache.put("key1", "value1");
        Optional<String> result = cache.get("key2");
        assertFalse(result.isPresent(), "get() for a different key must return Optional.empty()");
    }

    // -------------------------------------------------------------------------
    // put
    // -------------------------------------------------------------------------

    @Test
    void put_overwritesExistingValue() {
        cache.put("key1", "first");
        cache.put("key1", "second");
        assertEquals("second", cache.get("key1").orElse(null),
                "Second put() for the same key must overwrite the first value");
    }

    @Test
    void put_multipleKeys_allRetrievable() {
        cache.put("a", "alpha");
        cache.put("b", "beta");
        cache.put("c", "gamma");

        assertEquals("alpha", cache.get("a").orElse(null));
        assertEquals("beta", cache.get("b").orElse(null));
        assertEquals("gamma", cache.get("c").orElse(null));
    }

    // -------------------------------------------------------------------------
    // invalidate
    // -------------------------------------------------------------------------

    @Test
    void invalidate_removesEntry() {
        cache.put("key1", "value1");
        cache.invalidate("key1");
        assertFalse(cache.get("key1").isPresent(),
                "invalidate() must remove the entry so get() returns Optional.empty()");
    }

    @Test
    void invalidate_missingKey_isNoOp() {
        // Should not throw
        cache.invalidate("does-not-exist");
        // Other entries are unaffected
        cache.put("key2", "value2");
        assertEquals("value2", cache.get("key2").orElse(null));
    }

    @Test
    void invalidate_doesNotAffectOtherKeys() {
        cache.put("a", "alpha");
        cache.put("b", "beta");
        cache.invalidate("a");
        assertFalse(cache.get("a").isPresent(), "Invalidated key must be absent");
        assertTrue(cache.get("b").isPresent(), "Non-invalidated key must still be present");
    }

    // -------------------------------------------------------------------------
    // invalidateAll
    // -------------------------------------------------------------------------

    @Test
    void invalidateAll_clearsAllEntries() {
        cache.put("x", "X");
        cache.put("y", "Y");
        cache.put("z", "Z");

        cache.invalidateAll();

        assertFalse(cache.get("x").isPresent(), "All entries must be cleared after invalidateAll()");
        assertFalse(cache.get("y").isPresent(), "All entries must be cleared after invalidateAll()");
        assertFalse(cache.get("z").isPresent(), "All entries must be cleared after invalidateAll()");
    }

    @Test
    void invalidateAll_onEmptyCache_isNoOp() {
        // Must not throw
        cache.invalidateAll();
    }

    @Test
    void putAfterInvalidateAll_works() {
        cache.put("k", "v");
        cache.invalidateAll();
        cache.put("k", "new-value");
        assertEquals("new-value", cache.get("k").orElse(null),
                "put() after invalidateAll() must store the new value");
    }

    // -------------------------------------------------------------------------
    // Null key / value handling
    // -------------------------------------------------------------------------

    @Test
    void get_nullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void put_nullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
    }

    @Test
    void put_nullValue_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> cache.put("key", null));
    }

    @Test
    void invalidate_nullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> cache.invalidate(null));
    }

    // -------------------------------------------------------------------------
    // TTL expiry
    // -------------------------------------------------------------------------

    @Test
    void get_afterTtlExpiry_returnsEmpty() throws InterruptedException {
        CacheConfiguration shortTtl = new CacheConfiguration(100, Duration.ofMillis(100));
        CaffeineCacheProvider<String, String> shortCache = new CaffeineCacheProvider<>(shortTtl);

        shortCache.put("expiring", "value");
        assertTrue(shortCache.get("expiring").isPresent(), "Entry must be present before TTL expires");

        // Wait for expiry with some margin
        Thread.sleep(250);

        assertFalse(shortCache.get("expiring").isPresent(),
                "Entry must be absent after TTL has elapsed");
    }

    // -------------------------------------------------------------------------
    // maxSize eviction
    // -------------------------------------------------------------------------

    @Test
    void maxSize_whenExceeded_olderEntriesEvicted() throws InterruptedException {
        // Use a very small max size so eviction is easy to trigger
        CacheConfiguration tinyConfig = new CacheConfiguration(2, Duration.ofMinutes(5));
        CaffeineCacheProvider<String, String> tinyCache = new CaffeineCacheProvider<>(tinyConfig);

        tinyCache.put("k1", "v1");
        tinyCache.put("k2", "v2");
        tinyCache.put("k3", "v3"); // exceeds maxSize=2; should trigger eviction

        // Caffeine eviction is asynchronous — poll until eviction occurs
        int retries = 0;
        int presentCount = 999;
        while (presentCount > 2 && retries < 20) {
            Thread.sleep(50);
            presentCount = 0;
            if (tinyCache.get("k1").isPresent()) presentCount++;
            if (tinyCache.get("k2").isPresent()) presentCount++;
            if (tinyCache.get("k3").isPresent()) presentCount++;
            retries++;
        }

        assertTrue(presentCount <= 2,
                "Cache with maxSize=2 must have at most 2 entries after inserting 3 (found " + presentCount + ")");
    }

    // -------------------------------------------------------------------------
    // Concurrent access
    // -------------------------------------------------------------------------

    @Test
    void concurrentPutAndGet_maintainsCacheConsistency() throws Exception {
        CacheConfiguration config = new CacheConfiguration(10_000, Duration.ofSeconds(60));
        CaffeineCacheProvider<String, String> cache = new CaffeineCacheProvider<>(config);

        int threadCount = 50;
        int opsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "key-" + threadId + "-" + i;
                        String value = "value-" + threadId + "-" + i;
                        cache.put(key, value);
                        Optional<String> result = cache.get(key);
                        // Value should be present (we just put it) and correct
                        if (result.isEmpty() || !result.get().equals(value)) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // Start all threads simultaneously
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete within 10s");
        assertEquals(0, errors.get(), "No errors should occur during concurrent access");
    }
}
