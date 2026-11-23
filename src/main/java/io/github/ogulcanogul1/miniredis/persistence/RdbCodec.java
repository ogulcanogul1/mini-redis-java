package io.github.ogulcanogul1.miniredis.persistence;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.SnapshotEntry;
import io.github.ogulcanogul1.miniredis.storage.ZSetEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Big-endian binary snapshot codec: {@code MINIRDB\0} magic header, version, entry count, typed
 * length-prefixed records, and a trailing {@link CRC32} checksum (PLAN §3 Faz 8.2).
 */
public final class RdbCodec {

    private static final byte[] MAGIC = {'M', 'I', 'N', 'I', 'R', 'D', 'B', 0};
    private static final int VERSION = 1;

    private static final byte TYPE_STRING = 1;
    private static final byte TYPE_LIST = 2;
    private static final byte TYPE_HASH = 3;
    private static final byte TYPE_SET = 4;
    private static final byte TYPE_ZSET = 5;

    private RdbCodec() {}

    /** Encodes {@code entries} into a self-contained big-endian binary snapshot with trailing CRC32. */
    public static byte[] encode(List<SnapshotEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(entries.size());
            for (SnapshotEntry entry : entries) {
                writeEntry(out, entry);
            }
            out.flush();
            byte[] body = baos.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeLong(crc.getValue());
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decodes and verifies magic, version, and trailing CRC32; throws {@link RdbException} if corrupt. */
    public static List<SnapshotEntry> decode(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length < MAGIC.length + 16) {
            throw new RdbException("truncated RDB snapshot: " + data.length + " bytes");
        }
        int payloadLen = data.length - 8;
        ByteBuffer buf = ByteBuffer.wrap(data, 0, payloadLen).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new RdbException("invalid RDB magic header");
        }
        int version = buf.getInt();
        if (version != VERSION) {
            throw new RdbException("unsupported RDB version: " + version);
        }
        CRC32 crc = new CRC32();
        crc.update(data, 0, payloadLen);
        long expectedCrc =
                ByteBuffer.wrap(data, payloadLen, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        if (crc.getValue() != expectedCrc) {
            throw new RdbException("RDB CRC32 mismatch");
        }
        int count = buf.getInt();
        if (count < 0) {
            throw new RdbException("negative entry count: " + count);
        }
        List<SnapshotEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(readEntry(buf));
        }
        if (buf.hasRemaining()) {
            throw new RdbException("trailing bytes in RDB snapshot: " + buf.remaining());
        }
        return List.copyOf(entries);
    }

    private static void writeEntry(DataOutputStream out, SnapshotEntry entry) throws IOException {
        byte tag = switch (entry.value()) {
            case SnapshotEntry.StringVal ignored -> TYPE_STRING;
            case SnapshotEntry.ListVal ignored -> TYPE_LIST;
            case SnapshotEntry.HashVal ignored -> TYPE_HASH;
            case SnapshotEntry.SetVal ignored -> TYPE_SET;
            case SnapshotEntry.ZSetVal ignored -> TYPE_ZSET;
        };
        out.writeByte(tag);
        out.writeLong(entry.expireAtMillis());
        writeBytes(out, entry.key());
        switch (entry.value()) {
            case SnapshotEntry.StringVal s -> writeBytes(out, s.value());
            case SnapshotEntry.ListVal l -> {
                out.writeInt(l.elements().size());
                for (Bytes e : l.elements()) {
                    writeBytes(out, e);
                }
            }
            case SnapshotEntry.HashVal h -> {
                out.writeInt(h.fieldsAndValues().size());
                for (Bytes b : h.fieldsAndValues()) {
                    writeBytes(out, b);
                }
            }
            case SnapshotEntry.SetVal s -> {
                out.writeInt(s.members().size());
                for (Bytes m : s.members()) {
                    writeBytes(out, m);
                }
            }
            case SnapshotEntry.ZSetVal z -> {
                out.writeInt(z.entries().size());
                for (ZSetEntry ze : z.entries()) {
                    writeBytes(out, ze.member());
                    out.writeDouble(ze.score());
                }
            }
        }
    }

    private static SnapshotEntry readEntry(ByteBuffer buf) {
        if (buf.remaining() < 9) {
            throw new RdbException("truncated RDB entry header");
        }
        byte tag = buf.get();
        long expireAt = buf.getLong();
        Bytes key = readBytes(buf);
        SnapshotEntry.Value val = switch (tag) {
            case TYPE_STRING -> new SnapshotEntry.StringVal(readBytes(buf));
            case TYPE_LIST -> {
                int n = readCount(buf);
                List<Bytes> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(readBytes(buf));
                }
                yield new SnapshotEntry.ListVal(items);
            }
            case TYPE_HASH -> {
                int n = readCount(buf);
                List<Bytes> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(readBytes(buf));
                }
                yield new SnapshotEntry.HashVal(items);
            }
            case TYPE_SET -> {
                int n = readCount(buf);
                List<Bytes> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(readBytes(buf));
                }
                yield new SnapshotEntry.SetVal(items);
            }
            case TYPE_ZSET -> {
                int n = readCount(buf);
                List<ZSetEntry> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    Bytes m = readBytes(buf);
                    if (buf.remaining() < 8) {
                        throw new RdbException("truncated zset score");
                    }
                    double score = buf.getDouble();
                    items.add(new ZSetEntry(m, score));
                }
                yield new SnapshotEntry.ZSetVal(items);
            }
            default -> throw new RdbException("unknown RDB type tag: " + tag);
        };
        return new SnapshotEntry(key, expireAt, val);
    }

    private static void writeBytes(DataOutputStream out, Bytes b) throws IOException {
        byte[] raw = b.toByteArray();
        out.writeInt(raw.length);
        out.write(raw);
    }

    private static int readCount(ByteBuffer buf) {
        if (buf.remaining() < 4) {
            throw new RdbException("truncated RDB count");
        }
        int n = buf.getInt();
        if (n < 0) {
            throw new RdbException("negative RDB count: " + n);
        }
        return n;
    }

    private static Bytes readBytes(ByteBuffer buf) {
        int len = readCount(buf);
        if (buf.remaining() < len) {
            throw new RdbException("truncated RDB byte payload");
        }
        byte[] raw = new byte[len];
        buf.get(raw);
        return Bytes.of(raw);
    }
}
