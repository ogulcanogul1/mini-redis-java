package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.HashMap;
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
    public boolean exists(Bytes key) {
        Objects.requireNonNull(key, "key");
        return map.containsKey(key);
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
