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
