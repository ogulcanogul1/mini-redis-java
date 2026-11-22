package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The database: a map from keys to values. Commands reach the data only through this interface
 * (K1), so its implementation can change (HashMap, Dict, shards with locks in Faz 4) without
 * touching command code.
 *
 * <p>Keys and values are immutable {@link Bytes}, so a value returned by {@link #get} stays valid
 * after the call. Not thread-safe until Faz 4. Every method throws {@link NullPointerException} for
 * a {@code null} argument.
 */
public interface Keyspace {

    /** Returns the value of {@code key}, or {@link Optional#empty()} if the key does not exist. */
    Optional<Bytes> get(Bytes key);

    /** Sets {@code key} to {@code value}, replacing any old value. */
    void set(Bytes key, Bytes value);

    /** Deletes {@code key}. Returns {@code true} if it existed. */
    boolean delete(Bytes key);

    /** Returns whether {@code key} exists. */
    boolean exists(Bytes key);

    /** Returns the number of keys. */
    int size();

    /** Deletes every key. */
    void flush();

    /**
     * Calls {@code action} once for every key, in no defined order. {@code action} must not change
     * this keyspace.
     */
    void forEachKey(Consumer<? super Bytes> action);
}
