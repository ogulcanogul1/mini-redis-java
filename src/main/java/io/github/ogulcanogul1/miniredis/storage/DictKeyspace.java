package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** {@link Keyspace} on top of our own {@link Dict} (Adım 1.4, updated with TTL in Faz 6). */
final class DictKeyspace implements Keyspace {

    private final Dict<Bytes, RedisObject> dict = new Dict<>();
    private final Dict<Bytes, Long> expires = new Dict<>();
    private final Clock clock;

    DictKeyspace() {
        this(Clock.system());
    }

    DictKeyspace(Clock clock) {
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
        dict.put(key, RedisObject.string(value, now));
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
        dict.remove(key);
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
                dict.remove(key);
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
        RedisObject current = dict.get(key);
        IncrResult result = KeyspaceSupport.computeIncrement(current == null ? null : current.asString(), delta);
        if (result instanceof IncrResult.Ok ok) {
            dict.put(key, RedisObject.string(KeyspaceSupport.formatLong(ok.value()), now));
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
            dict.put(k, RedisObject.string(keysAndValues.get(i + 1), now));
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
        RedisObject val = dict.get(source);
        if (!source.equals(target)) {
            dict.remove(source);
            Long ttl = expires.remove(source);
            dict.put(target, val);
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
            dict.remove(key);
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
            dict.remove(key);
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
        long startNanos = clock.nanoTime();
        long now = clock.millis();
        int totalRemoved = 0;
        while (!expires.isEmpty()) {
            List<Bytes> sample = expires.sampleKeys(sampleSizePerShard, ThreadLocalRandom.current());
            int expiredInBatch = 0;
            for (Bytes key : sample) {
                if (KeyspaceSupport.isExpired(expires.get(key), now)) {
                    dict.remove(key);
                    expires.remove(key);
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
        return totalRemoved;
    }

    @Override
    public int size() {
        return dict.size();
    }

    @Override
    public void flush() {
        dict.clear();
        expires.clear();
    }

    @Override
    public void forEachKey(Consumer<? super Bytes> action) {
        Objects.requireNonNull(action, "action");
        long now = clock.millis();
        List<Bytes> live = new ArrayList<>(dict.size());
        for (Dict.Entry<Bytes, RedisObject> e : dict) {
            if (!KeyspaceSupport.isExpired(expires.get(e.key()), now)) {
                live.add(e.key());
            }
        }
        for (Bytes key : live) {
            action.accept(key);
        }
    }

    private RedisObject liveObject(Bytes key, long now) {
        RedisObject obj = dict.get(key);
        if (obj == null || KeyspaceSupport.isExpired(expires.get(key), now)) {
            return null;
        }
        return obj;
    }

    private boolean purgeIfExpired(Bytes key, long now) {
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
