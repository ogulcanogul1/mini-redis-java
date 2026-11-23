package io.github.ogulcanogul1.miniredis.ds;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * SipHash-2-4 keyed pseudorandom {@link Hasher} for {@link Bytes} keys, seeded with a 128-bit
 * random key per instance to defeat hash-flooding DoS attacks (PLAN §3 Faz 9).
 */
public final class SipHasher implements Hasher<Bytes> {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final long k0;
    private final long k1;

    /** Creates a {@code SipHasher} with a fresh cryptographically random 128-bit seed. */
    public SipHasher() {
        this(RANDOM.nextLong(), RANDOM.nextLong());
    }

    /** Creates a {@code SipHasher} with an explicit 128-bit seed (useful for deterministic tests). */
    public SipHasher(long k0, long k1) {
        this.k0 = k0;
        this.k1 = k1;
    }

    @Override
    public int hash(Bytes key) {
        Objects.requireNonNull(key, "key");
        long h = sipHash24(key.toByteArray());
        return (int) (h ^ (h >>> 32));
    }

    long sipHash24(byte[] data) {
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;

        int len = data.length;
        int blocks = len / 8;
        for (int i = 0; i < blocks; i++) {
            long m = loadLe64(data, i * 8);
            v3 ^= m;
            for (int r = 0; r < 2; r++) {
                v0 += v1;
                v1 = Long.rotateLeft(v1, 13);
                v1 ^= v0;
                v0 = Long.rotateLeft(v0, 32);
                v2 += v3;
                v3 = Long.rotateLeft(v3, 16);
                v3 ^= v2;
                v0 += v3;
                v3 = Long.rotateLeft(v3, 21);
                v3 ^= v0;
                v2 += v1;
                v1 = Long.rotateLeft(v1, 17);
                v1 ^= v2;
                v2 = Long.rotateLeft(v2, 32);
            }
            v0 ^= m;
        }

        long b = ((long) len & 0xffL) << 56;
        int tail = blocks * 8;
        int rem = len - tail;
        for (int i = 0; i < rem; i++) {
            b |= ((long) data[tail + i] & 0xffL) << (8 * i);
        }

        v3 ^= b;
        for (int r = 0; r < 2; r++) {
            v0 += v1;
            v1 = Long.rotateLeft(v1, 13);
            v1 ^= v0;
            v0 = Long.rotateLeft(v0, 32);
            v2 += v3;
            v3 = Long.rotateLeft(v3, 16);
            v3 ^= v2;
            v0 += v3;
            v3 = Long.rotateLeft(v3, 21);
            v3 ^= v0;
            v2 += v1;
            v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2;
            v2 = Long.rotateLeft(v2, 32);
        }
        v0 ^= b;

        v2 ^= 0xffL;
        for (int r = 0; r < 4; r++) {
            v0 += v1;
            v1 = Long.rotateLeft(v1, 13);
            v1 ^= v0;
            v0 = Long.rotateLeft(v0, 32);
            v2 += v3;
            v3 = Long.rotateLeft(v3, 16);
            v3 ^= v2;
            v0 += v3;
            v3 = Long.rotateLeft(v3, 21);
            v3 ^= v0;
            v2 += v1;
            v1 = Long.rotateLeft(v1, 17);
            v1 ^= v2;
            v2 = Long.rotateLeft(v2, 32);
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private static long loadLe64(byte[] data, int offset) {
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24)
                | (((long) data[offset + 4] & 0xffL) << 32)
                | (((long) data[offset + 5] & 0xffL) << 40)
                | (((long) data[offset + 6] & 0xffL) << 48)
                | (((long) data[offset + 7] & 0xffL) << 56);
    }
}
