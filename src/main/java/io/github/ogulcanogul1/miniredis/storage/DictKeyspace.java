package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** {@link Keyspace} on top of our own {@link Dict} (Adım 1.4). */
final class DictKeyspace implements Keyspace {

    private final Dict<Bytes, Bytes> dict = new Dict<>();

    @Override
    public Optional<Bytes> get(Bytes key) {
        return Optional.ofNullable(dict.get(key));
    }

    @Override
    public void set(Bytes key, Bytes value) {
        dict.put(key, value);
    }

    @Override
    public boolean delete(Bytes key) {
        return dict.remove(key) != null;
    }

    @Override
    public boolean exists(Bytes key) {
        return dict.containsKey(key);
    }

    @Override
    public int size() {
        return dict.size();
    }

    @Override
    public void flush() {
        dict.clear();
    }

    @Override
    public void forEachKey(Consumer<? super Bytes> action) {
        Objects.requireNonNull(action, "action");
        for (Dict.Entry<Bytes, Bytes> e : dict) {
            action.accept(e.key());
        }
    }
}
