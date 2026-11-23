package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The database: a map from keys to values. Commands reach the data only through this interface
 * (K1), so its implementation can change (HashMap, Dict, sharded with locks in Faz 4) without
 * touching command code.
 *
 * <p>Keys and values are immutable {@link Bytes}, so a value returned by {@link #get} stays valid
 * after the call. Thread-safe implementations ({@link Keyspaces#inMemory()},
 * {@link Keyspaces#sharded}) also execute multi-key operations atomically under sorted shard locks
 * (K3). Every method throws {@link NullPointerException} for a {@code null} argument.
 */
public interface Keyspace {

    /** Returns the value of {@code key}, or {@link Optional#empty()} if the key does not exist. */
    Optional<Bytes> get(Bytes key);

    /** Sets {@code key} to {@code value}, replacing any old value. */
    void set(Bytes key, Bytes value);

    /** Deletes {@code key}. Returns {@code true} if it existed. */
    boolean delete(Bytes key);

    /** Deletes every key in {@code keys} atomically, returning how many existed. */
    long deleteAll(List<Bytes> keys);

    /** Returns whether {@code key} exists. */
    boolean exists(Bytes key);

    /** Counts how many entries in {@code keys} exist atomically (repeated keys count each time). */
    long existsAll(List<Bytes> keys);

    /**
     * Atomically parses {@code key}'s value as a base-10 {@code long} (default {@code 0} when
     * absent), adds {@code delta}, and stores the updated value on success.
     */
    IncrResult incrBy(Bytes key, long delta);

    /** Atomically reads values for all {@code keys} in order. */
    List<Optional<Bytes>> mget(List<Bytes> keys);

    /**
     * Atomically writes alternating key/value pairs ({@code k1, v1, k2, v2, ...}).
     *
     * @throws IllegalArgumentException if {@code keysAndValues} has odd length
     */
    void mset(List<Bytes> keysAndValues);

    /**
     * Atomically renames {@code source} to {@code target}, overwriting {@code target} if present.
     *
     * @return {@code true} if {@code source} existed, {@code false} otherwise
     */
    boolean rename(Bytes source, Bytes target);

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
