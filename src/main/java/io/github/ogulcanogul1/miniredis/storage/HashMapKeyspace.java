package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** {@link Keyspace} on top of {@link HashMap} (Adım 1.3). Kept to run the contract tests on. */
final class HashMapKeyspace implements Keyspace {

    private final Map<Bytes, RedisObject> map = new HashMap<>();
    private final Map<Bytes, Long> expires = new HashMap<>();
    private final Clock clock;

    HashMapKeyspace() {
        this(Clock.system());
    }

    HashMapKeyspace(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<Bytes> get(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return Optional.empty();
        }
        obj.touch(now);
        return Optional.of(obj.asString());
    }

    @Override
    public KeyspaceResult<Optional<Bytes>> getString(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj != null) {
            obj.touch(now);
        }
        return KeyspaceSupport.readString(obj);
    }

    @Override
    public KeyspaceResult<Integer> listPush(Bytes key, List<Bytes> values, boolean left) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(values, "values");
        long now = clock.millis();
        purgeIfExpired(key, now);
        RedisObject existing = map.get(key);
        RedisObject.ListObj listObj;
        if (existing == null) {
            listObj = RedisObject.list(now);
            map.put(key, listObj);
        } else if (existing instanceof RedisObject.ListObj lo) {
            listObj = lo;
            listObj.touch(now);
        } else {
            return KeyspaceResult.wrongType();
        }
        return KeyspaceResult.ok(KeyspaceSupport.pushList(listObj, values, left));
    }

    @Override
    public KeyspaceResult<Optional<Bytes>> listPop(Bytes key, boolean left) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        if (!purgeIfExpired(key, now)) {
            return KeyspaceResult.ok(Optional.empty());
        }
        if (!(map.get(key) instanceof RedisObject.ListObj listObj)) {
            return KeyspaceResult.wrongType();
        }
        listObj.touch(now);
        Optional<Bytes> popped = KeyspaceSupport.popList(listObj, left);
        if (listObj.deque().isEmpty()) {
            map.remove(key);
            expires.remove(key);
        }
        return KeyspaceResult.ok(popped);
    }

    @Override
    public KeyspaceResult<List<Bytes>> listRange(Bytes key, long start, long stop) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(List.of());
        }
        if (!(obj instanceof RedisObject.ListObj listObj)) {
            return KeyspaceResult.wrongType();
        }
        listObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.rangeList(listObj, start, stop));
    }

    @Override
    public KeyspaceResult<Integer> listLen(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(0);
        }
        if (!(obj instanceof RedisObject.ListObj listObj)) {
            return KeyspaceResult.wrongType();
        }
        listObj.touch(now);
        return KeyspaceResult.ok(listObj.deque().size());
    }

    @Override
    public KeyspaceResult<Integer> hashSet(Bytes key, List<Bytes> fieldsAndValues) {
        Objects.requireNonNull(key, "key");
        KeyspaceSupport.validateMsetPairs(fieldsAndValues);
        long now = clock.millis();
        purgeIfExpired(key, now);
        RedisObject existing = map.get(key);
        RedisObject.HashObj hashObj;
        if (existing == null) {
            hashObj = RedisObject.hash(now);
            map.put(key, hashObj);
        } else if (existing instanceof RedisObject.HashObj ho) {
            hashObj = ho;
            hashObj.touch(now);
        } else {
            return KeyspaceResult.wrongType();
        }
        return KeyspaceResult.ok(KeyspaceSupport.setHash(hashObj, fieldsAndValues));
    }

    @Override
    public KeyspaceResult<Optional<Bytes>> hashGet(Bytes key, Bytes field) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(field, "field");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(Optional.empty());
        }
        if (!(obj instanceof RedisObject.HashObj hashObj)) {
            return KeyspaceResult.wrongType();
        }
        hashObj.touch(now);
        return KeyspaceResult.ok(Optional.ofNullable(hashObj.map().get(field)));
    }

    @Override
    public KeyspaceResult<Integer> hashDel(Bytes key, List<Bytes> fields) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fields, "fields");
        long now = clock.millis();
        if (!purgeIfExpired(key, now)) {
            return KeyspaceResult.ok(0);
        }
        if (!(map.get(key) instanceof RedisObject.HashObj hashObj)) {
            return KeyspaceResult.wrongType();
        }
        hashObj.touch(now);
        int removed = KeyspaceSupport.delHash(hashObj, fields);
        if (hashObj.map().isEmpty()) {
            map.remove(key);
            expires.remove(key);
        }
        return KeyspaceResult.ok(removed);
    }

    @Override
    public KeyspaceResult<List<Bytes>> hashGetAll(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(List.of());
        }
        if (!(obj instanceof RedisObject.HashObj hashObj)) {
            return KeyspaceResult.wrongType();
        }
        hashObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.getAllHash(hashObj));
    }

    @Override
    public KeyspaceResult<Integer> setAdd(Bytes key, List<Bytes> members) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(members, "members");
        long now = clock.millis();
        purgeIfExpired(key, now);
        RedisObject existing = map.get(key);
        RedisObject.SetObj setObj;
        if (existing == null) {
            setObj = RedisObject.set(now);
            map.put(key, setObj);
        } else if (existing instanceof RedisObject.SetObj so) {
            setObj = so;
            setObj.touch(now);
        } else {
            return KeyspaceResult.wrongType();
        }
        return KeyspaceResult.ok(KeyspaceSupport.addSet(setObj, members));
    }

    @Override
    public KeyspaceResult<Integer> setRem(Bytes key, List<Bytes> members) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(members, "members");
        long now = clock.millis();
        if (!purgeIfExpired(key, now)) {
            return KeyspaceResult.ok(0);
        }
        if (!(map.get(key) instanceof RedisObject.SetObj setObj)) {
            return KeyspaceResult.wrongType();
        }
        setObj.touch(now);
        int removed = KeyspaceSupport.remSet(setObj, members);
        if (setObj.members().isEmpty()) {
            map.remove(key);
            expires.remove(key);
        }
        return KeyspaceResult.ok(removed);
    }

    @Override
    public KeyspaceResult<Boolean> setIsMember(Bytes key, Bytes member) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(member, "member");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(false);
        }
        if (!(obj instanceof RedisObject.SetObj setObj)) {
            return KeyspaceResult.wrongType();
        }
        setObj.touch(now);
        return KeyspaceResult.ok(setObj.members().containsKey(member));
    }

    @Override
    public KeyspaceResult<List<Bytes>> setMembers(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(List.of());
        }
        if (!(obj instanceof RedisObject.SetObj setObj)) {
            return KeyspaceResult.wrongType();
        }
        setObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.allSetMembers(setObj));
    }

    @Override
    public KeyspaceResult<Integer> zadd(Bytes key, List<ZSetEntry> entries) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entries, "entries");
        long now = clock.millis();
        purgeIfExpired(key, now);
        RedisObject existing = map.get(key);
        RedisObject.ZSetObj zsetObj;
        if (existing == null) {
            zsetObj = RedisObject.zset(now);
            map.put(key, zsetObj);
        } else if (existing instanceof RedisObject.ZSetObj zo) {
            zsetObj = zo;
            zsetObj.touch(now);
        } else {
            return KeyspaceResult.wrongType();
        }
        return KeyspaceResult.ok(KeyspaceSupport.addZSet(zsetObj, entries));
    }

    @Override
    public KeyspaceResult<OptionalDouble> zscore(Bytes key, Bytes member) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(member, "member");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(OptionalDouble.empty());
        }
        if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
            return KeyspaceResult.wrongType();
        }
        zsetObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.scoreZSet(zsetObj, member));
    }

    @Override
    public KeyspaceResult<List<ZSetEntry>> zrange(Bytes key, long start, long stop) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(List.of());
        }
        if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
            return KeyspaceResult.wrongType();
        }
        zsetObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.rangeZSet(zsetObj, start, stop));
    }

    @Override
    public KeyspaceResult<List<ZSetEntry>> zrangeByScore(Bytes key, double min, double max) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(List.of());
        }
        if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
            return KeyspaceResult.wrongType();
        }
        zsetObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.rangeByScoreZSet(zsetObj, min, max));
    }

    @Override
    public KeyspaceResult<OptionalLong> zrank(Bytes key, Bytes member) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(member, "member");
        long now = clock.millis();
        RedisObject obj = liveObject(key, now);
        if (obj == null) {
            return KeyspaceResult.ok(OptionalLong.empty());
        }
        if (!(obj instanceof RedisObject.ZSetObj zsetObj)) {
            return KeyspaceResult.wrongType();
        }
        zsetObj.touch(now);
        return KeyspaceResult.ok(KeyspaceSupport.rankZSet(zsetObj, member));
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
        boolean exists = purgeIfExpired(key, now);
        if ((condition == SetCondition.IF_ABSENT && exists) || (condition == SetCondition.IF_PRESENT && !exists)) {
            return false;
        }
        map.put(key, RedisObject.string(value, now));
        if (expireAt == null) {
            expires.remove(key);
        } else {
            expires.put(key, expireAt);
        }
        return true;
    }

    @Override
    public boolean delete(Bytes key) {
        Objects.requireNonNull(key, "key");
        if (!purgeIfExpired(key, clock.millis())) {
            return false;
        }
        map.remove(key);
        expires.remove(key);
        return true;
    }

    @Override
    public long deleteAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long now = clock.millis();
        long deleted = 0;
        for (Bytes key : keys) {
            if (purgeIfExpired(key, now)) {
                map.remove(key);
                expires.remove(key);
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public boolean exists(Bytes key) {
        Objects.requireNonNull(key, "key");
        return liveObject(key, clock.millis()) != null;
    }

    @Override
    public long existsAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long now = clock.millis();
        long count = 0;
        for (Bytes key : keys) {
            if (liveObject(key, now) != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public IncrResult incrBy(Bytes key, long delta) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        purgeIfExpired(key, now);
        RedisObject current = map.get(key);
        IncrResult result = KeyspaceSupport.computeIncrement(current == null ? null : current.asString(), delta);
        if (result instanceof IncrResult.Ok ok) {
            map.put(key, RedisObject.string(KeyspaceSupport.formatLong(ok.value()), now));
        }
        return result;
    }

    @Override
    public List<Optional<Bytes>> mget(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long now = clock.millis();
        List<Optional<Bytes>> values = new ArrayList<>(keys.size());
        for (Bytes key : keys) {
            RedisObject obj = liveObject(key, now);
            if (obj == null) {
                values.add(Optional.empty());
            } else {
                obj.touch(now);
                values.add(Optional.of(obj.asString()));
            }
        }
        return List.copyOf(values);
    }

    @Override
    public void mset(List<Bytes> keysAndValues) {
        KeyspaceSupport.validateMsetPairs(keysAndValues);
        long now = clock.millis();
        for (int i = 0; i < keysAndValues.size(); i += 2) {
            Bytes k = keysAndValues.get(i);
            map.put(k, RedisObject.string(keysAndValues.get(i + 1), now));
            expires.remove(k);
        }
    }

    @Override
    public boolean rename(Bytes source, Bytes target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        long now = clock.millis();
        if (!purgeIfExpired(source, now)) {
            return false;
        }
        RedisObject val = map.get(source);
        if (!source.equals(target)) {
            map.remove(source);
            Long ttl = expires.remove(source);
            map.put(target, val);
            if (ttl == null) {
                expires.remove(target);
            } else {
                expires.put(target, ttl);
            }
        }
        return true;
    }

    @Override
    public boolean expire(Bytes key, long ttlMillis) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        Long expireAt = ttlMillis > 0 ? KeyspaceSupport.computeExpireAt(now, ttlMillis) : null;
        if (!purgeIfExpired(key, now)) {
            return false;
        }
        if (expireAt == null) {
            map.remove(key);
            expires.remove(key);
        } else {
            expires.put(key, expireAt);
        }
        return true;
    }

    @Override
    public boolean expireAt(Bytes key, long expireAtMillis) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        if (!purgeIfExpired(key, now)) {
            return false;
        }
        if (expireAtMillis <= now) {
            map.remove(key);
            expires.remove(key);
        } else {
            expires.put(key, expireAtMillis);
        }
        return true;
    }

    @Override
    public long pttl(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        if (liveObject(key, now) == null) {
            return -2;
        }
        Long expireAt = expires.get(key);
        return expireAt == null ? -1 : Math.max(0L, expireAt - now);
    }

    @Override
    public boolean persist(Bytes key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        if (!purgeIfExpired(key, now)) {
            return false;
        }
        return expires.remove(key) != null;
    }

    @Override
    public int purgeExpiredSample(int sampleSizePerShard, long timeBudgetNanos) {
        if (sampleSizePerShard < 1) {
            throw new IllegalArgumentException("sampleSizePerShard must be positive, got " + sampleSizePerShard);
        }
        long now = clock.millis();
        int removed = 0;
        Iterator<Map.Entry<Bytes, Long>> it = expires.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Bytes, Long> e = it.next();
            if (e.getValue() <= now) {
                map.remove(e.getKey());
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public long usedMemoryBytes() {
        long total = 0L;
        for (Map.Entry<Bytes, RedisObject> e : map.entrySet()) {
            total += MemoryEstimator.estimateEntry(e.getKey(), e.getValue());
        }
        return total;
    }

    @Override
    public int evictIfNeeded(long maxMemoryBytes, EvictionPolicy policy, int sampleSize) {
        Objects.requireNonNull(policy, "policy");
        if (maxMemoryBytes <= 0 || policy == EvictionPolicy.NOEVICTION) {
            return 0;
        }
        boolean volatileOnly = policy == EvictionPolicy.VOLATILE_LRU || policy == EvictionPolicy.VOLATILE_RANDOM;
        boolean lru = policy == EvictionPolicy.ALLKEYS_LRU || policy == EvictionPolicy.VOLATILE_LRU;
        long used = usedMemoryBytes();
        int evicted = 0;
        while (used > maxMemoryBytes) {
            Bytes victim = null;
            long oldestLru = Long.MAX_VALUE;
            for (Bytes k : volatileOnly ? expires.keySet() : map.keySet()) {
                RedisObject obj = map.get(k);
                if (obj == null) {
                    continue;
                }
                if (!lru) {
                    victim = k;
                    break;
                }
                if (obj.lru() < oldestLru) {
                    oldestLru = obj.lru();
                    victim = k;
                }
            }
            if (victim == null) {
                break;
            }
            RedisObject removed = map.remove(victim);
            expires.remove(victim);
            if (removed != null) {
                used -= MemoryEstimator.estimateEntry(victim, removed);
                evicted++;
            }
        }
        return evicted;
    }

    @Override
    public void flush() {
        map.clear();
        expires.clear();
    }

    @Override
    public void forEachKey(Consumer<? super Bytes> action) {
        Objects.requireNonNull(action, "action");
        long now = clock.millis();
        List<Bytes> live = new ArrayList<>(map.size());
        for (Bytes key : map.keySet()) {
            if (!KeyspaceSupport.isExpired(expires.get(key), now)) {
                live.add(key);
            }
        }
        for (Bytes key : live) {
            action.accept(key);
        }
    }

    private RedisObject liveObject(Bytes key, long now) {
        RedisObject obj = map.get(key);
        if (obj == null || KeyspaceSupport.isExpired(expires.get(key), now)) {
            return null;
        }
        return obj;
    }

    private boolean purgeIfExpired(Bytes key, long now) {
        if (!map.containsKey(key)) {
            return false;
        }
        if (KeyspaceSupport.isExpired(expires.get(key), now)) {
            map.remove(key);
            expires.remove(key);
            return false;
        }
        return true;
    }
}
