package io.github.ogulcanogul1.miniredis.persistence;

import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays an existing AOF at startup, truncating a partial tail record with a {@code WARN} log
 * while failing fast on mid-file corruption (K12, PLAN §3 Faz 8.1).
 */
public final class AofLoader {

    private static final Logger log = LoggerFactory.getLogger(AofLoader.class);

    private AofLoader() {}

    /**
     * Loads and replays {@code path} if it exists, returning the number of replayed commands.
     */
    public static int load(Path path, RespLimits limits, CommandTable table, CommandContext ctx) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return 0;
        }
        try (AofChannel channel = new FileAofChannel(path)) {
            return load(channel, limits, table, ctx);
        }
    }

    /**
     * Loads and replays all valid commands from {@code channel}, truncating any partial tail record.
     */
    public static int load(AofChannel channel, RespLimits limits, CommandTable table, CommandContext ctx)
            throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(ctx, "ctx");

        long size = channel.size();
        if (size == 0L) {
            return 0;
        }
        byte[] data = readAll(channel, size);
        AofCodec.DecodeResult decoded = AofCodec.decodeAll(data, limits);
        if (decoded.truncatedTail()) {
            log.warn("AOF truncated partial tail record: keeping {} of {} bytes", decoded.validBytes(), size);
            channel.truncate(decoded.validBytes());
            channel.force(false);
        }
        Dispatcher replayDispatcher = new Dispatcher(table, ctx);
        int count = 0;
        for (List<Bytes> argv : decoded.commands()) {
            replayDispatcher.dispatch(argv);
            count++;
        }
        return count;
    }

    private static byte[] readAll(AofChannel channel, long size) throws IOException {
        if (size > Integer.MAX_VALUE - 8) {
            throw new AofException("AOF file too large to load: " + size + " bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        ByteBuffer buf = ByteBuffer.allocate(8192);
        long pos = 0L;
        while (pos < size) {
            buf.clear();
            int n = channel.read(buf, pos);
            if (n < 0) {
                break;
            }
            out.write(buf.array(), 0, n);
            pos += n;
        }
        return out.toByteArray();
    }
}
