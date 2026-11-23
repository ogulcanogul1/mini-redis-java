package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** {@link Keyspace} on top of {@link HashMap} (Adım 1.3). Kept to run the contract tests on. */
final class HashMapKeyspace implements Keyspace {

    private final Map<Bytes, Bytes> map = new HashMap<>();

    @Override
    public Optional<Bytes> get(Bytes key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(map.get(key));
    }

    @Override
    public void set(Bytes key, Bytes value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        map.put(key, value);
    }

    @Override
    public boolean delete(Bytes key) {
        Objects.requireNonNull(key, "key");
        return map.remove(key) != null;
    }

    @Override
    public long deleteAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long deleted = 0;
        for (Bytes key : keys) {
            if (map.remove(key) != null) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public boolean exists(Bytes key) {
        Objects.requireNonNull(key, "key");
        return map.containsKey(key);
    }

    @Override
    public long existsAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long count = 0;
        for (Bytes key : keys) {
            if (map.containsKey(key)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public IncrResult incrBy(Bytes key, long delta) {
        Objects.requireNonNull(key, "key");
        IncrResult result = KeyspaceSupport.computeIncrement(map.get(key), delta);
        if (result instanceof IncrResult.Ok ok) {
            map.put(key, KeyspaceSupport.formatLong(ok.value()));
        }
        return result;
    }

    @Override
    public List<Optional<Bytes>> mget(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        List<Optional<Bytes>> values = new ArrayList<>(keys.size());
        for (Bytes key : keys) {
            values.add(Optional.ofNullable(map.get(key)));
        }
        return List.copyOf(values);
    }

    @Override
    public void mset(List<Bytes> keysAndValues) {
        KeyspaceSupport.validateMsetPairs(keysAndValues);
        for (int i = 0; i < keysAndValues.size(); i += 2) {
            map.put(keysAndValues.get(i), keysAndValues.get(i + 1));
        }
    }

    @Override
    public boolean rename(Bytes source, Bytes target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Bytes val = map.get(source);
        if (val == null) {
            return false;
        }
        if (!source.equals(target)) {
            map.remove(source);
            map.put(target, val);
        }
        return true;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void flush() {
        map.clear();
    }

    @Override
    public void forEachKey(Consumer<? super Bytes> action) {
        Objects.requireNonNull(action, "action");
        map.keySet().forEach(action);
    }
}
