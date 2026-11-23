package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import java.util.ArrayList;
import java.util.List;
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
    public long deleteAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long deleted = 0;
        for (Bytes key : keys) {
            if (dict.remove(key) != null) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public boolean exists(Bytes key) {
        return dict.containsKey(key);
    }

    @Override
    public long existsAll(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        long count = 0;
        for (Bytes key : keys) {
            if (dict.containsKey(key)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public IncrResult incrBy(Bytes key, long delta) {
        Objects.requireNonNull(key, "key");
        IncrResult result = KeyspaceSupport.computeIncrement(dict.get(key), delta);
        if (result instanceof IncrResult.Ok ok) {
            dict.put(key, KeyspaceSupport.formatLong(ok.value()));
        }
        return result;
    }

    @Override
    public List<Optional<Bytes>> mget(List<Bytes> keys) {
        KeyspaceSupport.validateNonNullKeys(keys);
        List<Optional<Bytes>> values = new ArrayList<>(keys.size());
        for (Bytes key : keys) {
            values.add(Optional.ofNullable(dict.get(key)));
        }
        return List.copyOf(values);
    }

    @Override
    public void mset(List<Bytes> keysAndValues) {
        KeyspaceSupport.validateMsetPairs(keysAndValues);
        for (int i = 0; i < keysAndValues.size(); i += 2) {
            dict.put(keysAndValues.get(i), keysAndValues.get(i + 1));
        }
    }

    @Override
    public boolean rename(Bytes source, Bytes target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Bytes val = dict.get(source);
        if (val == null) {
            return false;
        }
        if (!source.equals(target)) {
            dict.remove(source);
            dict.put(target, val);
        }
        return true;
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
