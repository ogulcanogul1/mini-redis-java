package io.github.ogulcanogul1.miniredis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.SnapshotEntry;
import io.github.ogulcanogul1.miniredis.storage.ZSetEntry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class RdbCodecTest {

    @Test
    void roundTripsAllValueTypesAndTtl() {
        List<SnapshotEntry> entries = List.of(
                new SnapshotEntry(
                        Bytes.utf8("str"),
                        1_700_000_000_000L,
                        new SnapshotEntry.StringVal(Bytes.utf8("hello\r\nworld"))),
                new SnapshotEntry(
                        Bytes.utf8("list"), 0L, new SnapshotEntry.ListVal(List.of(Bytes.utf8("a"), Bytes.utf8("b")))),
                new SnapshotEntry(
                        Bytes.utf8("hash"), 0L, new SnapshotEntry.HashVal(List.of(Bytes.utf8("f1"), Bytes.utf8("v1")))),
                new SnapshotEntry(
                        Bytes.utf8("set"), 0L, new SnapshotEntry.SetVal(List.of(Bytes.utf8("m1"), Bytes.utf8("m2")))),
                new SnapshotEntry(
                        Bytes.utf8("zset"),
                        1_800_000_000_000L,
                        new SnapshotEntry.ZSetVal(List.of(
                                new ZSetEntry(Bytes.utf8("alice"), 1.5), new ZSetEntry(Bytes.utf8("bob"), 2.5)))));

        byte[] encoded = RdbCodec.encode(entries);
        List<SnapshotEntry> decoded = RdbCodec.decode(encoded);
        assertEquals(entries, decoded);
    }

    @Test
    void roundTripsEmptySnapshot() {
        byte[] encoded = RdbCodec.encode(List.of());
        assertTrue(RdbCodec.decode(encoded).isEmpty());
    }

    @Test
    void rejectsCorruptedChecksum() {
        byte[] encoded = RdbCodec.encode(
                List.of(new SnapshotEntry(Bytes.utf8("k"), 0L, new SnapshotEntry.StringVal(Bytes.utf8("v")))));
        encoded[encoded.length - 1] ^= 0x01;
        assertThrows(RdbException.class, () -> RdbCodec.decode(encoded));
    }

    @Test
    void rejectsInvalidMagicHeader() {
        byte[] encoded = RdbCodec.encode(List.of());
        encoded[0] = 'X';
        assertThrows(RdbException.class, () -> RdbCodec.decode(encoded));
    }

    @Test
    void rejectsUnsupportedVersion() {
        byte[] encoded = RdbCodec.encode(List.of());
        ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putInt(8, 99);
        assertThrows(RdbException.class, () -> RdbCodec.decode(encoded));
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] encoded = RdbCodec.encode(
                List.of(new SnapshotEntry(Bytes.utf8("k"), 0L, new SnapshotEntry.StringVal(Bytes.utf8("v")))));
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 5);
        assertThrows(RdbException.class, () -> RdbCodec.decode(truncated));
    }
}
