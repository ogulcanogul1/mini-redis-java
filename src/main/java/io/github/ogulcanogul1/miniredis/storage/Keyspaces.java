package io.github.ogulcanogul1.miniredis.storage;

/** Creates {@link Keyspace} instances. The implementations themselves are package-private. */
public final class Keyspaces {

    public static final int DEFAULT_SHARDS = 16;

    private Keyspaces() {}

    /** Returns a new, empty thread-safe in-memory keyspace ({@code 16} shards, {@link LockType#RWLOCK}). */
    public static Keyspace inMemory() {
        return sharded(DEFAULT_SHARDS, LockType.RWLOCK);
    }

    /** Returns a new thread-safe {@link Keyspace} using the given shard count and lock strategy (K3). */
    public static Keyspace sharded(int shards, LockType lockType) {
        return new ShardedKeyspace(shards, lockType);
    }
}
