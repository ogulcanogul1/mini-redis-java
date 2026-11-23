package io.github.ogulcanogul1.miniredis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClusterSlotsTest {

    @Test
    void matchesRedis7HashSlotsAndHashTags() {
        assertEquals(12739, ClusterSlots.keySlot("123456789".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3443, ClusterSlots.keySlot("{user1000}.following".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3443, ClusterSlots.keySlot("user1000".getBytes(StandardCharsets.UTF_8)));
        assertEquals(14292, ClusterSlots.keySlot("foo{}bar".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                ClusterSlots.keySlot("a".getBytes(StandardCharsets.UTF_8)),
                ClusterSlots.keySlot("{a}{b}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                ClusterSlots.keySlot("{unclosed".getBytes(StandardCharsets.UTF_8)),
                ClusterSlots.keySlot("{unclosed".getBytes(StandardCharsets.UTF_8)));
    }
}
