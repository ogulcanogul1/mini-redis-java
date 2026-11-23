package io.github.ogulcanogul1.miniredis.ds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SipHasherTest {

    @Test
    void matchesStandardSipHash24TestVector() {
        // Jean-Philippe Aumasson & Daniel J. Bernstein SipHash-2-4 test vector (15-byte input)
        long k0 = 0x0706050403020100L;
        long k1 = 0x0f0e0d0c0b0a0908L;
        SipHasher hasher = new SipHasher(k0, k1);
        byte[] in = new byte[15];
        for (int i = 0; i < in.length; i++) {
            in[i] = (byte) i;
        }
        assertEquals(0xa129ca6149be45e5L, hasher.sipHash24(in));
    }

    @Test
    void equalKeysHaveEqualHashesAndDistinctSeedsDiverge() {
        SipHasher h1 = new SipHasher();
        SipHasher h2 = new SipHasher();
        Bytes key = Bytes.utf8("attack-collision-key");
        assertEquals(h1.hash(key), h1.hash(Bytes.utf8("attack-collision-key")));
        assertNotEquals(h1.hash(key), h2.hash(key));
    }
}
