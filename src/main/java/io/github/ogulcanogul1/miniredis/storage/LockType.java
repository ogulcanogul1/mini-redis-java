package io.github.ogulcanogul1.miniredis.storage;

/** Locking strategy for {@link Keyspaces#sharded(int, LockType)} (K3). */
public enum LockType {
    /** Single {@link java.util.concurrent.locks.ReentrantLock} per shard. */
    MUTEX,
    /** {@link java.util.concurrent.locks.ReentrantReadWriteLock} per shard. */
    RWLOCK
}
