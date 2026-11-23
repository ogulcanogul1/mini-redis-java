package io.github.ogulcanogul1.miniredis.util;

import java.util.Objects;

/**
 * Redis Cluster hash slot calculation: CRC16-CCITT (XModem, {@code poly=0x1021}, {@code init=0})
 * modulo {@value #SLOT_COUNT} with {@code {tag}} hash-tag extraction.
 */
public final class ClusterSlots {

    public static final int SLOT_COUNT = 16_384;
    private static final int[] CRC16_TABLE = buildTable();

    private ClusterSlots() {}

    /** Computes the Redis Cluster hash slot in {@code [0, 16383]} for {@code key}. */
    public static int keySlot(byte[] key) {
        Objects.requireNonNull(key, "key");
        int start = 0;
        while (start < key.length && key[start] != '{') {
            start++;
        }
        if (start == key.length) {
            return crc16(key, 0, key.length) & (SLOT_COUNT - 1);
        }
        int end = start + 1;
        while (end < key.length && key[end] != '}') {
            end++;
        }
        if (end == key.length || end == start + 1) {
            return crc16(key, 0, key.length) & (SLOT_COUNT - 1);
        }
        return crc16(key, start + 1, end - start - 1) & (SLOT_COUNT - 1);
    }

    /** Computes CRC16-CCITT (XModem) over {@code data[off .. off + len)}. */
    public static int crc16(byte[] data, int off, int len) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(off, len, data.length);
        int crc = 0;
        for (int i = 0; i < len; i++) {
            int idx = ((crc >>> 8) ^ (data[off + i] & 0xff)) & 0xff;
            crc = ((crc << 8) ^ CRC16_TABLE[idx]) & 0xffff;
        }
        return crc;
    }

    private static int[] buildTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xffff;
                } else {
                    crc = (crc << 1) & 0xffff;
                }
            }
            table[i] = crc;
        }
        return table;
    }
}
