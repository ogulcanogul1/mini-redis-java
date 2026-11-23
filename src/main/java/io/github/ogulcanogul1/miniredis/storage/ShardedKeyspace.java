package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import io.github.ogulcanogul1.miniredis.ds.Hasher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe sharded {@link Keyspace} (K3).
 *
 * <p>Single-key operations lock only the target shard ({@code hash & (N - 1)}). Multi-key
 * operations ({@link #deleteAll}, {@link #existsAll}, {@link #mget}, {@link #mset},
 * {@link #rename}, {@link #flush}) acquire distinct shard locks in strictly ascending index order
 * and release them in reverse order so deadlock is impossible (global lock ordering).
 */
final class ShardedKeyspace implements Keyspace {

    private final Shard[] shards;
    private final int mask;
    private final Hasher<Bytes> hasher = Hasher.standard();

    ShardedKeyspace(int shardCount, LockType lockType) {
        if (shardCount < 1 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shardCount must be a positive power of two, got " + shardCount);
        }
        Objects.requireNonNull(lockType, "lockType");
        this.shards = new Shard[shardCount];
        for (int i = 0; i < shardCount; i++) {
            this.shards[i] = new Shard(lockType);
        }
        this.mask = shardCount - 1;
    }

    @Override
    public Optional<Bytes> get(Bytes key) {
        Objects.requireNonNull(key, "key");
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            return Optional.ofNullable(s.dict.get(key));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public void set(Bytes key, Bytes value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            s.dict.put(key, value);
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public boolean delete(Bytes key) {
        Objects.requireNonNull(key, "key");
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            return s.dict.remove(key) != null;
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public long deleteAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        if (keys.isEmpty()) {
            return 0;
        }
        int[] indices = sortedUniqueShards(keys, 1);
        return withShardLocks(indices, true, () -> {
            long deleted = 0;
            for (Bytes key : keys) {
                if (shardFor(key).dict.remove(key) != null) {
                    deleted++;
                }
            }
            return deleted;
        });
    }

    @Override
    public boolean exists(Bytes key) {
        Objects.requireNonNull(key, "key");
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            return s.dict.containsKey(key);
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public long existsAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        if (keys.isEmpty()) {
            return 0;
        }
        int[] indices = sortedUniqueShards(keys, 1);
        return withShardLocks(indices, false, () -> {
            long count = 0;
            for (Bytes key : keys) {
                if (shardFor(key).dict.containsKey(key)) {
                    count++;
                }
            }
            return count;
        });
    }

    @Override
    public IncrResult incrBy(Bytes key, long delta) {
        Objects.requireNonNull(key, "key");
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            IncrResult result = KeyspaceSupport.computeIncrement(s.dict.get(key), delta);
            if (result instanceof IncrResult.Ok ok) {
                s.dict.put(key, KeyspaceSupport.formatLong(ok.value()));
            }
            return result;
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public List<Optional<Bytes>> mget(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        if (keys.isEmpty()) {
            return List.of();
        }
        int[] indices = sortedUniqueShards(keys, 1);
        return withShardLocks(indices, false, () -> {
            List<Optional<Bytes>> values = new ArrayList<>(keys.size());
            for (Bytes key : keys) {
                values.add(Optional.ofNullable(shardFor(key).dict.get(key)));
            }
            return List.copyOf(values);
        });
    }

    @Override
    public void mset(List<Bytes> keysAndValues) {
        KeyspaceSupport.validateMsetPairs(keysAndValues);
        if (keysAndValues.isEmpty()) {
            return;
        }
        int[] indices = sortedUniqueShards(keysAndValues, 2);
        withShardLocks(indices, true, () -> {
            for (int i = 0; i < keysAndValues.size(); i += 2) {
                Bytes k = keysAndValues.get(i);
                shardFor(k).dict.put(k, keysAndValues.get(i + 1));
            }
            return null;
        });
    }

    @Override
    public boolean rename(Bytes source, Bytes target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        int sIdx = shardIndex(source);
        int tIdx = shardIndex(target);
        int[] indices =
                sIdx == tIdx ? new int[] {sIdx} : (sIdx < tIdx ? new int[] {sIdx, tIdx} : new int[] {tIdx, sIdx});
        return withShardLocks(indices, true, () -> {
            Shard srcShard = shards[sIdx];
            Bytes val = srcShard.dict.get(source);
            if (val == null) {
                return false;
            }
            if (!source.equals(target)) {
                srcShard.dict.remove(source);
                shards[tIdx].dict.put(target, val);
            }
            return true;
        });
    }

    @Override
    public int size() {
        int total = 0;
        for (Shard s : shards) {
            s.readLock.lock();
            try {
                total += s.dict.size();
            } finally {
                s.readLock.unlock();
            }
        }
        return total;
    }

    @Override
    public void flush() {
        int[] all = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            all[i] = i;
        }
        withShardLocks(all, true, () -> {
            for (Shard s : shards) {
                s.dict.clear();
            }
            return null;
        });
    }

    @Override
    public void forEachKey(Consumer<? super Bytes> action) {
        Objects.requireNonNull(action, "action");
        // Snapshot one shard's keys at a time so user actions never run while holding a shard lock.
        for (Shard s : shards) {
            List<Bytes> shardKeys;
            s.readLock.lock();
            try {
                shardKeys = new ArrayList<>(s.dict.size());
                for (Dict.Entry<Bytes, Bytes> e : s.dict) {
                    shardKeys.add(e.key());
                }
            } finally {
                s.readLock.unlock();
            }
            for (Bytes key : shardKeys) {
                action.accept(key);
            }
        }
    }

    private int shardIndex(Bytes key) {
        return hasher.hash(key) & mask;
    }

    private Shard shardFor(Bytes key) {
        return shards[shardIndex(key)];
    }

    private int[] sortedUniqueShards(List<Bytes> list, int step) {
        int n = list.size() / step;
        int[] raw = new int[n];
        for (int i = 0; i < n; i++) {
            raw[i] = shardIndex(list.get(i * step));
        }
        Arrays.sort(raw);
        int unique = 1;
        for (int i = 1; i < n; i++) {
            if (raw[i] != raw[unique - 1]) {
                raw[unique++] = raw[i];
            }
        }
        return unique == n ? raw : Arrays.copyOf(raw, unique);
    }

    private <R> R withShardLocks(int[] indices, boolean write, Supplier<R> action) {
        Lock[] acquired = new Lock[indices.length];
        int locked = 0;
        try {
            for (int idx : indices) {
                Shard s = shards[idx];
                Lock lock = write ? s.writeLock : s.readLock;
                lock.lock();
                acquired[locked++] = lock;
            }
            return action.get();
        } finally {
            for (int i = locked - 1; i >= 0; i--) {
                acquired[i].unlock();
            }
        }
    }

    private static final class Shard {
        private final Dict<Bytes, Bytes> dict = new Dict<>();
        private final Lock readLock;
        private final Lock writeLock;

        Shard(LockType lockType) {
            if (lockType == LockType.MUTEX) {
                ReentrantLock mutex = new ReentrantLock();
                this.readLock = mutex;
                this.writeLock = mutex;
            } else {
                ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
                this.readLock = rw.readLock();
                this.writeLock = rw.writeLock();
            }
        }
    }
}
