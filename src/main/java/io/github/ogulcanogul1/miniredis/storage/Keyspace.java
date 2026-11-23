package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The database: a map from keys to values. Commands reach the data only through this interface
 * (K1), so its implementation can change (HashMap, Dict, sharded with locks in Faz 4) without
 * touching command code.
 *
 * <p>Keys and values are immutable {@link Bytes}, so a value returned by {@link #get} stays valid
 * after the call. Thread-safe implementations ({@link Keyspaces#inMemory()},
 * {@link Keyspaces#sharded}) also execute multi-key operations atomically under sorted shard locks
 * (K3) and support lazy + active TTL expiration (K6, K8). Every method throws {@link
 * NullPointerException} for a {@code null} argument.
 */
public interface Keyspace {

    /** Returns the value of {@code key}, or {@link Optional#empty()} if absent or expired. */
    Optional<Bytes> get(Bytes key);

    /** Returns the string value of {@code key}, or {@link KeyspaceResult.WrongType} if another type. */
    KeyspaceResult<Optional<Bytes>> getString(Bytes key);

    /** Pushes {@code values} to the head ({@code left=true}) or tail of a list, returning its new length. */
    KeyspaceResult<Integer> listPush(Bytes key, List<Bytes> values, boolean left);

    /** Pops one element from the head ({@code left=true}) or tail of a list, deleting the key if empty. */
    KeyspaceResult<Optional<Bytes>> listPop(Bytes key, boolean left);

    /** Returns elements in inclusive {@code [start, stop]} (supporting negative indices) of a list. */
    KeyspaceResult<List<Bytes>> listRange(Bytes key, long start, long stop);

    /** Returns the length of the list at {@code key} ({@code 0} if absent or expired). */
    KeyspaceResult<Integer> listLen(Bytes key);

    /** Sets field/value pairs in a hash, returning the number of newly added fields. */
    KeyspaceResult<Integer> hashSet(Bytes key, List<Bytes> fieldsAndValues);

    /** Returns the value of {@code field} in the hash at {@code key}. */
    KeyspaceResult<Optional<Bytes>> hashGet(Bytes key, Bytes field);

    /** Removes {@code fields} from the hash at {@code key}, deleting the key if empty. */
    KeyspaceResult<Integer> hashDel(Bytes key, List<Bytes> fields);

    /** Returns alternating {@code field, value} pairs of the hash at {@code key}. */
    KeyspaceResult<List<Bytes>> hashGetAll(Bytes key);

    /** Adds {@code members} to the set at {@code key}, returning the count of newly added members. */
    KeyspaceResult<Integer> setAdd(Bytes key, List<Bytes> members);

    /** Removes {@code members} from the set at {@code key}, deleting the key if empty. */
    KeyspaceResult<Integer> setRem(Bytes key, List<Bytes> members);

    /** Returns whether {@code member} belongs to the set at {@code key}. */
    KeyspaceResult<Boolean> setIsMember(Bytes key, Bytes member);

    /** Returns all members of the set at {@code key} (empty list if absent). */
    KeyspaceResult<List<Bytes>> setMembers(Bytes key);

    /** Adds or updates {@code entries} in the sorted set at {@code key}, returning newly added count. */
    KeyspaceResult<Integer> zadd(Bytes key, List<ZSetEntry> entries);

    /** Returns the score of {@code member} in the sorted set at {@code key}. */
    KeyspaceResult<OptionalDouble> zscore(Bytes key, Bytes member);

    /** Returns entries in inclusive 0-based index range {@code [start, stop]} of the sorted set. */
    KeyspaceResult<List<ZSetEntry>> zrange(Bytes key, long start, long stop);

    /** Returns entries with score in {@code [min, max]} of the sorted set. */
    KeyspaceResult<List<ZSetEntry>> zrangeByScore(Bytes key, double min, double max);

    /** Returns the 0-based rank of {@code member} in the sorted set at {@code key}. */
    KeyspaceResult<OptionalLong> zrank(Bytes key, Bytes member);

    /** Sets {@code key} to {@code value} unconditionally, clearing any existing TTL. */
    void set(Bytes key, Bytes value);

    /**
     * Conditionally sets {@code key} to {@code value} with an optional relative TTL in milliseconds.
     *
     * @param ttlMillis {@code null} for no expiration (removes any old TTL), or a positive number
     *     of milliseconds
     * @return {@code true} if the key was written, {@code false} if {@code condition} was not met
     * @throws IllegalArgumentException if {@code ttlMillis != null} and {@code ttlMillis <= 0} or
     *     adding it to the current time overflows {@code long}
     */
    boolean set(Bytes key, Bytes value, SetCondition condition, Long ttlMillis);

    /** Deletes {@code key} (if present and not expired). Returns {@code true} if it existed. */
    boolean delete(Bytes key);

    /** Deletes every live key in {@code keys} atomically, returning how many existed. */
    long deleteAll(List<Bytes> keys);

    /** Returns whether {@code key} exists and has not expired. */
    boolean exists(Bytes key);

    /** Counts how many entries in {@code keys} are live atomically (repeated keys count each time). */
    long existsAll(List<Bytes> keys);

    /**
     * Atomically parses {@code key}'s value as a base-10 {@code long} (default {@code 0} when
     * absent or expired), adds {@code delta}, and stores the updated value on success, preserving
     * any existing TTL on a live key.
     */
    IncrResult incrBy(Bytes key, long delta);

    /** Atomically reads values for all {@code keys} in order ({@link Optional#empty()} if expired). */
    List<Optional<Bytes>> mget(List<Bytes> keys);

    /**
     * Atomically writes alternating key/value pairs ({@code k1, v1, k2, v2, ...}), clearing any
     * existing TTL on each written key.
     *
     * @throws IllegalArgumentException if {@code keysAndValues} has odd length
     */
    void mset(List<Bytes> keysAndValues);

    /**
     * Atomically renames {@code source} to {@code target}, overwriting {@code target} if present
     * and moving {@code source}'s TTL (if any) to {@code target}.
     *
     * @return {@code true} if {@code source} existed and was not expired, {@code false} otherwise
     */
    boolean rename(Bytes source, Bytes target);

    /**
     * Sets a relative expiration of {@code ttlMillis} milliseconds on {@code key}. If {@code
     * ttlMillis <= 0} and {@code key} is live, deletes it immediately and returns {@code true}.
     *
     * @return {@code true} if {@code key} existed and was not expired, {@code false} otherwise
     * @throws IllegalArgumentException if {@code ttlMillis > 0} overflows {@code long} when added to
     *     the current time
     */
    boolean expire(Bytes key, long ttlMillis);

    /**
     * Sets an absolute Unix epoch expiration timestamp (ms) on {@code key}. If {@code
     * expireAtMillis <= now} and {@code key} is live, deletes it immediately and returns {@code
     * true}.
     *
     * @return {@code true} if {@code key} existed and was not expired, {@code false} otherwise
     */
    boolean expireAt(Bytes key, long expireAtMillis);

    /**
     * Returns the remaining time-to-live in milliseconds: {@code -2} if {@code key} is absent or
     * expired, {@code -1} if it has no expiration, or {@code >= 0} milliseconds otherwise.
     */
    long pttl(Bytes key);

    /**
     * Removes the expiration from {@code key} if present.
     *
     * @return {@code true} if {@code key} was live and had an expiration removed, {@code false}
     *     otherwise
     */
    boolean persist(Bytes key);

    /**
     * Samples up to {@code sampleSizePerShard} expiring keys per shard (repeating on a shard when
     * more than 25% of sampled keys are expired) until {@code timeBudgetNanos} elapses.
     *
     * @return total number of expired keys removed
     */
    int purgeExpiredSample(int sampleSizePerShard, long timeBudgetNanos);

    /** Returns the number of keys (approximate under concurrent lazy expiration, K6). */
    int size();

    /** Returns estimated heap bytes occupied by stored keys and values. */
    long usedMemoryBytes();

    /**
     * Evicts keys according to {@code policy} using {@code sampleSize} candidates per step until
     * {@link #usedMemoryBytes()} is at or below {@code maxMemoryBytes} or no eligible keys remain.
     *
     * @return number of evicted keys
     */
    int evictIfNeeded(long maxMemoryBytes, EvictionPolicy policy, int sampleSize);

    /** Deletes every key and expiration. */
    void flush();

    /**
     * Calls {@code action} once for every live key, in no defined order. {@code action} must not
     * change this keyspace.
     */
    void forEachKey(Consumer<? super Bytes> action);

    /** Returns an immutable shallow-copy snapshot of all currently live entries (K5). */
    List<SnapshotEntry> snapshotEntries();

    /** Restores entries from a snapshot, skipping already-expired entries. */
    void restoreSnapshot(List<SnapshotEntry> entries);

    /**
     * Returns a monotonically increasing version token for {@code key} used by optimistic locking
     * ({@code WATCH}/{@code EXEC}).
     */
    default long keyVersion(Bytes key) {
        return 0L;
    }
}
