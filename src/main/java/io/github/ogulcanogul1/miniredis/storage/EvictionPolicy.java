package io.github.ogulcanogul1.miniredis.storage;

/**
 * Memory eviction policy applied when {@code maxmemory > 0} and {@link Keyspace#usedMemoryBytes()}
 * exceeds the limit (PLAN §3 Faz 6.2).
 */
public enum EvictionPolicy {
    /** Reject write commands with {@code OOM} when over {@code maxmemory}. */
    NOEVICTION,
    /** Evict approximately least-recently-used keys from any key. */
    ALLKEYS_LRU,
    /** Evict approximately least-recently-used keys among keys with a TTL. */
    VOLATILE_LRU,
    /** Evict random keys from any key. */
    ALLKEYS_RANDOM,
    /** Evict random keys among keys with a TTL. */
    VOLATILE_RANDOM
}
