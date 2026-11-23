package io.github.ogulcanogul1.miniredis.ds;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * A hash table with separate chaining. Not thread-safe.
 *
 * <p>The capacity is always a power of two and starts at {@value #INITIAL_CAPACITY}. When {@code
 * size} exceeds the capacity, the table doubles (load factor 1). It stops growing at {@value
 * #MAX_CAPACITY}; after that, chains just get longer. The table never shrinks.
 *
 * <p>{@link #get} and the iterator are pure reads: they never change the table (K3). The iterator
 * is fail-fast: a change made during iteration, other than through {@link Iterator#remove()}, makes
 * the next call throw {@link ConcurrentModificationException}. This is best effort, not a
 * guarantee.
 *
 * @param <K> the key type, never {@code null}
 * @param <V> the value type, never {@code null}
 */
public final class Dict<K, V> implements Iterable<Dict.Entry<K, V>> {

    static final int INITIAL_CAPACITY = 4;
    static final int MAX_CAPACITY = 1 << 30;

    /** A key-value pair. Read-only for callers. */
    public static final class Entry<K, V> {
        private final K key;
        private final int hash;
        private V value;
        private Entry<K, V> next;

        private Entry(K key, int hash, V value, Entry<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }

        public K key() {
            return key;
        }

        public V value() {
            return value;
        }
    }

    private final Hasher<? super K> hasher;
    private Entry<K, V>[] table;
    private int size;
    private int modCount;

    /** Creates an empty dict that uses {@link Hasher#standard()}. */
    public Dict() {
        this(Hasher.standard());
    }

    /** Creates an empty dict that uses {@code hasher}. */
    public Dict(Hasher<? super K> hasher) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.table = newTable(INITIAL_CAPACITY);
    }

    /**
     * Maps {@code key} to {@code value}.
     *
     * @return the previous value, or {@code null} if there was none
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        int h = hasher.hash(key);
        int i = h & (table.length - 1);
        for (Entry<K, V> e = table[i]; e != null; e = e.next) {
            if (e.hash == h && e.key.equals(key)) {
                V old = e.value;
                e.value = value;
                return old;
            }
        }
        table[i] = new Entry<>(key, h, value, table[i]);
        size++;
        modCount++;
        if (size > table.length && table.length < MAX_CAPACITY) {
            resize(table.length << 1);
        }
        return null;
    }

    /**
     * Returns the value for {@code key}, or {@code null} if there is none. Does not change the table.
     *
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public V get(K key) {
        Entry<K, V> e = find(key);
        return e == null ? null : e.value;
    }

    /** Returns whether {@code key} is present. Does not change the table. */
    public boolean containsKey(K key) {
        return find(key) != null;
    }

    /**
     * Removes {@code key}.
     *
     * @return the removed value, or {@code null} if the key was not present
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        int h = hasher.hash(key);
        int i = h & (table.length - 1);
        Entry<K, V> prev = null;
        for (Entry<K, V> e = table[i]; e != null; prev = e, e = e.next) {
            if (e.hash == h && e.key.equals(key)) {
                if (prev == null) {
                    table[i] = e.next;
                } else {
                    prev.next = e.next;
                }
                size--;
                modCount++;
                return e.value;
            }
        }
        return null;
    }

    /** Removes every entry and goes back to the initial capacity. */
    public void clear() {
        table = newTable(INITIAL_CAPACITY);
        size = 0;
        modCount++;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns up to {@code maxCount} distinct keys starting from a random bucket, without
     * modifying the table (used by active expiration, K6).
     *
     * @throws IllegalArgumentException if {@code maxCount < 1}
     */
    public List<K> sampleKeys(int maxCount, RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (maxCount < 1) {
            throw new IllegalArgumentException("maxCount must be positive, got " + maxCount);
        }
        if (size == 0) {
            return List.of();
        }
        int target = Math.min(maxCount, size);
        List<K> result = new ArrayList<>(target);
        int mask = table.length - 1;
        int start = random.nextInt(table.length);
        for (int step = 0; step < table.length && result.size() < target; step++) {
            for (Entry<K, V> e = table[(start + step) & mask]; e != null && result.size() < target; e = e.next) {
                result.add(e.key);
            }
        }
        return result;
    }

    /** The current number of buckets. Package-private, for tests. */
    int capacity() {
        return table.length;
    }

    /** Returns a fail-fast iterator that visits every entry exactly once, in no defined order. */
    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new EntryIterator();
    }

    private Entry<K, V> find(K key) {
        Objects.requireNonNull(key, "key");
        int h = hasher.hash(key);
        for (Entry<K, V> e = table[h & (table.length - 1)]; e != null; e = e.next) {
            // The int compare is cheap and rules out most entries before the equals call.
            if (e.hash == h && e.key.equals(key)) {
                return e;
            }
        }
        return null;
    }

    private void resize(int newCapacity) {
        Entry<K, V>[] newTable = newTable(newCapacity);
        int mask = newCapacity - 1;
        for (Entry<K, V> head : table) {
            Entry<K, V> e = head;
            while (e != null) {
                Entry<K, V> next = e.next; // save it before e is relinked into the new table
                int i = e.hash & mask;
                e.next = newTable[i];
                newTable[i] = e;
                e = next;
            }
        }
        table = newTable;
    }

    // Java cannot create an array of a generic type (type erasure). The cast is safe: the array
    // only ever holds Entry<K, V> and never leaves this class.
    @SuppressWarnings("unchecked")
    private static <K, V> Entry<K, V>[] newTable(int capacity) {
        return (Entry<K, V>[]) new Entry<?, ?>[capacity];
    }

    private final class EntryIterator implements Iterator<Entry<K, V>> {
        private final Entry<K, V>[] tab = table;
        private int expectedModCount = modCount;
        private int bucket;
        private Entry<K, V> next;
        private Entry<K, V> lastReturned;

        EntryIterator() {
            advance();
        }

        private void advance() {
            while (next == null && bucket < tab.length) {
                next = tab[bucket++];
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Entry<K, V> next() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            Entry<K, V> e = next;
            if (e == null) {
                throw new NoSuchElementException();
            }
            next = e.next;
            advance();
            lastReturned = e;
            return e;
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException("next() has not been called, or remove() was already called");
            }
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            Dict.this.remove(lastReturned.key);
            lastReturned = null;
            expectedModCount = modCount;
        }
    }
}
