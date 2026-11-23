package io.github.ogulcanogul1.miniredis.ds;

/**
 * Computes the hash a {@link Dict} uses to pick a bucket (Strategy). Faz 9 plugs SipHash in here.
 *
 * @param <K> the key type
 */
@FunctionalInterface
public interface Hasher<K> {

    /** Returns the hash of {@code key}. Equal keys must have equal hashes. */
    int hash(K key);

    /**
     * Returns the default hasher: {@code key.hashCode()} with its high bits mixed into the low bits
     * ({@code h ^ (h >>> 16)}, as in {@code HashMap}). The table uses only the low bits, so without
     * the mix, keys whose hashes differ only in the high bits would all land in one bucket.
     */
    static <K> Hasher<K> standard() {
        return key -> {
            int h = key.hashCode();
            return h ^ (h >>> 16);
        };
    }

    /** Returns a SipHash-2-4 {@link Hasher} for {@link Bytes} with a random startup seed. */
    static Hasher<Bytes> sipHash() {
        return new SipHasher();
    }
}
