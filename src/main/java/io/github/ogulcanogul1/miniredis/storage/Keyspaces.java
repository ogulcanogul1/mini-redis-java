package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.util.Clock;

/** Creates {@link Keyspace} instances. The implementations themselves are package-private. */
public final class Keyspaces {

    public static final int DEFAULT_SHARDS = 16;

    private Keyspaces() {}

    /** Returns a new, empty thread-safe in-memory keyspace ({@code 16} shards, {@link LockType#RWLOCK}). */
    public static Keyspace inMemory() {
        return inMemory(Clock.system());
    }

    /** Returns a new, empty thread-safe in-memory keyspace using the given {@link Clock}. */
    public static Keyspace inMemory(Clock clock) {
        return sharded(DEFAULT_SHARDS, LockType.RWLOCK, clock);
    }

    /** Returns a new thread-safe {@link Keyspace} using the given shard count and lock strategy (K3). */
    public static Keyspace sharded(int shards, LockType lockType) {
        return sharded(shards, lockType, Clock.system());
    }

    /** Returns a new thread-safe {@link Keyspace} using the given shard count, lock strategy, and {@link Clock}. */
    public static Keyspace sharded(int shards, LockType lockType, Clock clock) {
        return new ShardedKeyspace(shards, lockType, clock);
    }
}
