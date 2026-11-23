package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import io.github.ogulcanogul1.miniredis.ds.Hasher;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe sharded {@link Keyspace} with lazy and active expiration (K3, K6, K8).
 *
 * <p>Single-key operations lock only the target shard ({@code hash & (N - 1)}). Multi-key
 * operations acquire distinct shard locks in strictly ascending index order and release them in
 * reverse order so deadlock is impossible (global lock ordering). Read-lock operations never mutate
 * or delete expired entries (K6); expired keys are cleaned up under write locks.
 */
final class ShardedKeyspace implements Keyspace {

    private final Shard[] shards;
    private final int mask;
    private final Hasher<Bytes> hasher = Hasher.standard();
    private final Clock clock;

    ShardedKeyspace(int shardCount, LockType lockType) {
        this(shardCount, lockType, Clock.system());
    }

    ShardedKeyspace(int shardCount, LockType lockType, Clock clock) {
        if (shardCount < 1 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shardCount must be a positive power of two, got " + shardCount);
        }
        Objects.requireNonNull(lockType, "lockType");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shards = new Shard[shardCount];
        for (int i = 0; i < shardCount; i++) {
            this.shards[i] = new Shard(lockType);
        }
        this.mask = shardCount - 1;
    }

    @Override
    public Optional<Bytes> get(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return Optional.empty();
            }
            obj.touch(now);
            return Optional.of(obj.asString());
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Optional<Bytes>> getString(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj != null) {
                obj.touch(now);
            }
            return KeyspaceSupport.readString(obj);
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> listPush(Bytes key, List<Bytes> values, boolean left) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(values, "values");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            s.purgeIfExpired(key, now);
            RedisObject existing = s.dict.get(key);
            RedisObject.ListObj listObj;
            if (existing == null) {
                listObj = RedisObject.list(now);
                s.dict.put(key, listObj);
            } else if (existing instanceof RedisObject.ListObj lo) {
                listObj = lo;
                listObj.touch(now);
            } else {
                return KeyspaceResult.wrongType();
            }
            return KeyspaceResult.ok(KeyspaceSupport.pushList(listObj, values, left));
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Optional<Bytes>> listPop(Bytes key, boolean left) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return KeyspaceResult.ok(Optional.empty());
            }
            if (!(s.dict.get(key) instanceof RedisObject.ListObj listObj)) {
                return KeyspaceResult.wrongType();
            }
            listObj.touch(now);
            Optional<Bytes> popped = KeyspaceSupport.popList(listObj, left);
            if (listObj.deque().isEmpty()) {
                s.dict.remove(key);
                s.expires.remove(key);
            }
            return KeyspaceResult.ok(popped);
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<List<Bytes>> listRange(Bytes key, long start, long stop) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(List.of());
            }
            if (!(obj instanceof RedisObject.ListObj listObj)) {
                return KeyspaceResult.wrongType();
            }
            listObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.rangeList(listObj, start, stop));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> listLen(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(0);
            }
            if (!(obj instanceof RedisObject.ListObj listObj)) {
                return KeyspaceResult.wrongType();
            }
            listObj.touch(now);
            return KeyspaceResult.ok(listObj.deque().size());
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> hashSet(Bytes key, List<Bytes> fieldsAndValues) {
        Objects.requireNonNull(key, "key");
        KeyspaceSupport.validateMsetPairs(fieldsAndValues);
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            s.purgeIfExpired(key, now);
            RedisObject existing = s.dict.get(key);
            RedisObject.HashObj hashObj;
            if (existing == null) {
                hashObj = RedisObject.hash(now);
                s.dict.put(key, hashObj);
            } else if (existing instanceof RedisObject.HashObj ho) {
                hashObj = ho;
                hashObj.touch(now);
            } else {
                return KeyspaceResult.wrongType();
            }
            return KeyspaceResult.ok(KeyspaceSupport.setHash(hashObj, fieldsAndValues));
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Optional<Bytes>> hashGet(Bytes key, Bytes field) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(field, "field");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(Optional.empty());
            }
            if (!(obj instanceof RedisObject.HashObj hashObj)) {
                return KeyspaceResult.wrongType();
            }
            hashObj.touch(now);
            return KeyspaceResult.ok(Optional.ofNullable(hashObj.map().get(field)));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> hashDel(Bytes key, List<Bytes> fields) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fields, "fields");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return KeyspaceResult.ok(0);
            }
            if (!(s.dict.get(key) instanceof RedisObject.HashObj hashObj)) {
                return KeyspaceResult.wrongType();
            }
            hashObj.touch(now);
            int removed = KeyspaceSupport.delHash(hashObj, fields);
            if (hashObj.map().isEmpty()) {
                s.dict.remove(key);
                s.expires.remove(key);
            }
            return KeyspaceResult.ok(removed);
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<List<Bytes>> hashGetAll(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(List.of());
            }
            if (!(obj instanceof RedisObject.HashObj hashObj)) {
                return KeyspaceResult.wrongType();
            }
            hashObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.getAllHash(hashObj));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> setAdd(Bytes key, List<Bytes> members) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(members, "members");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            s.purgeIfExpired(key, now);
            RedisObject existing = s.dict.get(key);
            RedisObject.SetObj setObj;
            if (existing == null) {
                setObj = RedisObject.set(now);
                s.dict.put(key, setObj);
            } else if (existing instanceof RedisObject.SetObj so) {
                setObj = so;
                setObj.touch(now);
            } else {
                return KeyspaceResult.wrongType();
            }
            return KeyspaceResult.ok(KeyspaceSupport.addSet(setObj, members));
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> setRem(Bytes key, List<Bytes> members) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(members, "members");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return KeyspaceResult.ok(0);
            }
            if (!(s.dict.get(key) instanceof RedisObject.SetObj setObj)) {
                return KeyspaceResult.wrongType();
            }
            setObj.touch(now);
            int removed = KeyspaceSupport.remSet(setObj, members);
            if (setObj.members().isEmpty()) {
                s.dict.remove(key);
                s.expires.remove(key);
            }
            return KeyspaceResult.ok(removed);
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Boolean> setIsMember(Bytes key, Bytes member) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(member, "member");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(false);
            }
            if (!(obj instanceof RedisObject.SetObj setObj)) {
                return KeyspaceResult.wrongType();
            }
            setObj.touch(now);
            return KeyspaceResult.ok(setObj.members().containsKey(member));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<List<Bytes>> setMembers(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(List.of());
            }
            if (!(obj instanceof RedisObject.SetObj setObj)) {
                return KeyspaceResult.wrongType();
            }
            setObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.allSetMembers(setObj));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<Integer> zadd(Bytes key, List<ZSetEntry> entries) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entries, "entries");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            s.purgeIfExpired(key, now);
            RedisObject existing = s.dict.get(key);
            RedisObject.ZSetObj zsetObj;
            if (existing == null) {
                zsetObj = RedisObject.zset(now);
                s.dict.put(key, zsetObj);
            } else if (existing instanceof RedisObject.ZSetObj zo) {
                zsetObj = zo;
                zsetObj.touch(now);
            } else {
                return KeyspaceResult.wrongType();
            }
            return KeyspaceResult.ok(KeyspaceSupport.addZSet(zsetObj, entries));
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<OptionalDouble> zscore(Bytes key, Bytes member) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(member, "member");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(OptionalDouble.empty());
            }
            if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
                return KeyspaceResult.wrongType();
            }
            zsetObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.scoreZSet(zsetObj, member));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<List<ZSetEntry>> zrange(Bytes key, long start, long stop) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(List.of());
            }
            if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
                return KeyspaceResult.wrongType();
            }
            zsetObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.rangeZSet(zsetObj, start, stop));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<List<ZSetEntry>> zrangeByScore(Bytes key, double min, double max) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(List.of());
            }
            if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
                return KeyspaceResult.wrongType();
            }
            zsetObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.rangeByScoreZSet(zsetObj, min, max));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public KeyspaceResult<OptionalLong> zrank(Bytes key, Bytes member) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(member, "member");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            RedisObject obj = s.liveObject(key, now);
            if (obj == null) {
                return KeyspaceResult.ok(OptionalLong.empty());
            }
            if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
                return KeyspaceResult.wrongType();
            }
            zsetObj.touch(now);
            return KeyspaceResult.ok(KeyspaceSupport.rankZSet(zsetObj, member));
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public void set(Bytes key, Bytes value) {
        set(key, value, SetCondition.ALWAYS, null);
    }

    @Override
    public boolean set(Bytes key, Bytes value, SetCondition condition, Long ttlMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(condition, "condition");
        long now = clock.millis();
        Long expireAt = ttlMillis == null ? null : KeyspaceSupport.computeExpireAt(now, ttlMillis);
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            boolean exists = s.purgeIfExpired(key, now);
            if ((condition == SetCondition.IF_ABSENT && exists) || (condition == SetCondition.IF_PRESENT && !exists)) {
                return false;
            }
            s.dict.put(key, RedisObject.string(value, now));
            if (expireAt == null) {
                s.expires.remove(key);
            } else {
                s.expires.put(key, expireAt);
            }
            return true;
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public boolean delete(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return false;
            }
            s.dict.remove(key);
            s.expires.remove(key);
            return true;
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
        long now = clock.millis();
        int[] indices = sortedUniqueShards(keys, 1);
        return withShardLocks(indices, true, () -> {
            long deleted = 0;
            for (Bytes key : keys) {
                Shard s = shardFor(key);
                if (s.purgeIfExpired(key, now)) {
                    s.dict.remove(key);
                    s.expires.remove(key);
                    deleted++;
                }
            }
            return deleted;
        });
    }

    @Override
    public boolean exists(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            return s.liveObject(key, now) != null;
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
        long now = clock.millis();
        int[] indices = sortedUniqueShards(keys, 1);
        return withShardLocks(indices, false, () -> {
            long count = 0;
            for (Bytes key : keys) {
                if (shardFor(key).liveObject(key, now) != null) {
                    count++;
                }
            }
            return count;
        });
    }

    @Override
    public IncrResult incrBy(Bytes key, long delta) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            s.purgeIfExpired(key, now);
            RedisObject current = s.dict.get(key);
            IncrResult result = KeyspaceSupport.computeIncrement(current == null ? null : current.asString(), delta);
            if (result instanceof IncrResult.Ok ok) {
                s.dict.put(key, RedisObject.string(KeyspaceSupport.formatLong(ok.value()), now));
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
        long now = clock.millis();
        int[] indices = sortedUniqueShards(keys, 1);
        return withShardLocks(indices, false, () -> {
            List<Optional<Bytes>> values = new ArrayList<>(keys.size());
            for (Bytes key : keys) {
                RedisObject obj = shardFor(key).liveObject(key, now);
                if (obj == null) {
                    values.add(Optional.empty());
                } else {
                    obj.touch(now);
                    values.add(Optional.of(obj.asString()));
                }
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
        long now = clock.millis();
        int[] indices = sortedUniqueShards(keysAndValues, 2);
        withShardLocks(indices, true, () -> {
            for (int i = 0; i < keysAndValues.size(); i += 2) {
                Bytes k = keysAndValues.get(i);
                Shard s = shardFor(k);
                s.dict.put(k, RedisObject.string(keysAndValues.get(i + 1), now));
                s.expires.remove(k);
            }
            return null;
        });
    }

    @Override
    public boolean rename(Bytes source, Bytes target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        long now = clock.millis();
        int sIdx = shardIndex(source);
        int tIdx = shardIndex(target);
        int[] indices =
                sIdx == tIdx ? new int[] {sIdx} : (sIdx < tIdx ? new int[] {sIdx, tIdx} : new int[] {tIdx, sIdx});
        return withShardLocks(indices, true, () -> {
            Shard srcShard = shards[sIdx];
            if (!srcShard.purgeIfExpired(source, now)) {
                return false;
            }
            RedisObject val = srcShard.dict.get(source);
            if (!source.equals(target)) {
                srcShard.dict.remove(source);
                Long ttl = srcShard.expires.remove(source);
                Shard dstShard = shards[tIdx];
                dstShard.dict.put(target, val);
                if (ttl == null) {
                    dstShard.expires.remove(target);
                } else {
                    dstShard.expires.put(target, ttl);
                }
            }
            return true;
        });
    }

    @Override
    public boolean expire(Bytes key, long ttlMillis) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Long expireAt = ttlMillis > 0 ? KeyspaceSupport.computeExpireAt(now, ttlMillis) : null;
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return false;
            }
            if (expireAt == null) {
                s.dict.remove(key);
                s.expires.remove(key);
            } else {
                s.expires.put(key, expireAt);
            }
            return true;
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public boolean expireAt(Bytes key, long expireAtMillis) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return false;
            }
            if (expireAtMillis <= now) {
                s.dict.remove(key);
                s.expires.remove(key);
            } else {
                s.expires.put(key, expireAtMillis);
            }
            return true;
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public long pttl(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.readLock.lock();
        try {
            if (s.liveObject(key, now) == null) {
                return -2;
            }
            Long expireAt = s.expires.get(key);
            return expireAt == null ? -1 : Math.max(0L, expireAt - now);
        } finally {
            s.readLock.unlock();
        }
    }

    @Override
    public boolean persist(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Shard s = shardFor(key);
        s.writeLock.lock();
        try {
            if (!s.purgeIfExpired(key, now)) {
                return false;
            }
            return s.expires.remove(key) != null;
        } finally {
            s.writeLock.unlock();
        }
    }

    @Override
    public int purgeExpiredSample(int sampleSizePerShard, long timeBudgetNanos) {
        if (sampleSizePerShard < 1) {
            throw new IllegalArgumentException("sampleSizePerShard must be positive, got " + sampleSizePerShard);
        }
        long startNanos = clock.nanoTime();
        long now = clock.millis();
        int totalRemoved = 0;
        for (Shard s : shards) {
            if (timeBudgetNanos > 0 && clock.nanoTime() - startNanos >= timeBudgetNanos) {
                break;
            }
            s.writeLock.lock();
            try {
                while (!s.expires.isEmpty()) {
                    List<Bytes> sample = s.expires.sampleKeys(sampleSizePerShard, ThreadLocalRandom.current());
                    int expiredInBatch = 0;
                    for (Bytes key : sample) {
                        if (KeyspaceSupport.isExpired(s.expires.get(key), now)) {
                            s.dict.remove(key);
                            s.expires.remove(key);
                            expiredInBatch++;
                            totalRemoved++;
                        }
                    }
                    if (expiredInBatch * 4 <= sample.size()) {
                        break;
                    }
                    if (timeBudgetNanos > 0 && clock.nanoTime() - startNanos >= timeBudgetNanos) {
                        break;
                    }
                }
            } finally {
                s.writeLock.unlock();
            }
        }
        return totalRemoved;
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
    public long usedMemoryBytes() {
        long total = 0L;
        for (Shard s : shards) {
            s.readLock.lock();
            try {
                for (Dict.Entry<Bytes, RedisObject> e : s.dict) {
                    total += MemoryEstimator.estimateEntry(e.key(), e.value());
                }
            } finally {
                s.readLock.unlock();
            }
        }
        return total;
    }

    @Override
    public int evictIfNeeded(long maxMemoryBytes, EvictionPolicy policy, int sampleSize) {
        Objects.requireNonNull(policy, "policy");
        if (sampleSize < 1) {
            throw new IllegalArgumentException("sampleSize must be positive, got " + sampleSize);
        }
        if (maxMemoryBytes <= 0 || policy == EvictionPolicy.NOEVICTION) {
            return 0;
        }
        boolean volatileOnly = policy == EvictionPolicy.VOLATILE_LRU || policy == EvictionPolicy.VOLATILE_RANDOM;
        boolean lru = policy == EvictionPolicy.ALLKEYS_LRU || policy == EvictionPolicy.VOLATILE_LRU;
        long used = usedMemoryBytes();
        int evicted = 0;
        while (used > maxMemoryBytes) {
            boolean progress = false;
            for (Shard s : shards) {
                if (used <= maxMemoryBytes) {
                    break;
                }
                s.writeLock.lock();
                try {
                    List<Bytes> sample =
                            (volatileOnly ? s.expires : s.dict).sampleKeys(sampleSize, ThreadLocalRandom.current());
                    if (sample.isEmpty()) {
                        continue;
                    }
                    Bytes victim = pickVictim(s.dict, sample, lru);
                    if (victim == null) {
                        continue;
                    }
                    RedisObject removed = s.dict.remove(victim);
                    s.expires.remove(victim);
                    if (removed != null) {
                        used -= MemoryEstimator.estimateEntry(victim, removed);
                        evicted++;
                        progress = true;
                    }
                } finally {
                    s.writeLock.unlock();
                }
            }
            if (!progress) {
                break;
            }
        }
        return evicted;
    }

    private static Bytes pickVictim(Dict<Bytes, RedisObject> dict, List<Bytes> sample, boolean lru) {
        if (!lru) {
            return sample.getFirst();
        }
        Bytes best = null;
        long oldestLru = Long.MAX_VALUE;
        for (Bytes key : sample) {
            RedisObject obj = dict.get(key);
            if (obj != null && (best == null || obj.lru() < oldestLru)) {
                oldestLru = obj.lru();
                best = key;
            }
        }
        return best;
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
                s.expires.clear();
            }
            return null;
        });
    }

    @Override
    public void forEachKey(Consumer<? super Bytes> action) {
        Objects.requireNonNull(action, "action");
        long now = clock.millis();
        // Snapshot one shard's live keys at a time so user actions never run while holding a shard lock.
        for (Shard s : shards) {
            List<Bytes> shardKeys;
            s.readLock.lock();
            try {
                shardKeys = new ArrayList<>(s.dict.size());
                for (Dict.Entry<Bytes, RedisObject> e : s.dict) {
                    if (!KeyspaceSupport.isExpired(s.expires.get(e.key()), now)) {
                        shardKeys.add(e.key());
                    }
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
        private final Dict<Bytes, RedisObject> dict = new Dict<>();
        private final Dict<Bytes, Long> expires = new Dict<>();
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

        RedisObject liveObject(Bytes key, long now) {
            RedisObject obj = dict.get(key);
            if (obj == null || KeyspaceSupport.isExpired(expires.get(key), now)) {
                return null;
            }
            return obj;
        }

        boolean purgeIfExpired(Bytes key, long now) {
            if (!dict.containsKey(key)) {
                return false;
            }
            if (KeyspaceSupport.isExpired(expires.get(key), now)) {
                dict.remove(key);
                expires.remove(key);
                return false;
            }
            return true;
        }
    }
}
