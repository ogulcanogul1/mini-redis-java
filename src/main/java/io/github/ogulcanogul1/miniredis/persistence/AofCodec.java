package io.github.ogulcanogul1.miniredis.persistence;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.ParseResult;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.protocol.RespParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes write commands as RESP2 multibulk arrays for the AOF and decodes an AOF byte stream back
 * into commands, detecting partial tail records vs mid-file corruption (PLAN §3 Faz 8.1).
 */
public final class AofCodec {

    private static final byte[] CRLF = {'\r', '\n'};

    private AofCodec() {}

    /** Encoded commands and the byte offset of the last complete command. */
    public record DecodeResult(List<List<Bytes>> commands, long validBytes, boolean truncatedTail) {
        public DecodeResult {
            commands = List.copyOf(commands);
        }
    }

    /** Encodes {@code argv} as a RESP2 multibulk array ({@code *N\r\n$len\r\n...\r\n}). */
    public static byte[] encodeCommand(List<Bytes> argv) {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write('*');
        out.writeBytes(Integer.toString(argv.size()).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(CRLF);
        for (Bytes arg : argv) {
            Objects.requireNonNull(arg, "arg");
            byte[] data = arg.toByteArray();
            out.write('$');
            out.writeBytes(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(CRLF);
            out.writeBytes(data);
            out.writeBytes(CRLF);
        }
        return out.toByteArray();
    }

    /**
     * Decodes every complete command in {@code data}. A partial command at the very end is reported
     * via {@link DecodeResult#truncatedTail()} and {@link DecodeResult#validBytes()}; a protocol
     * error throws {@link AofException} immediately (fail-fast).
     */
    public static DecodeResult decodeAll(byte[] data, RespLimits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        if (data.length == 0) {
            return new DecodeResult(List.of(), 0L, false);
        }
        RespParser parser = new RespParser(limits);
        parser.feed(data, 0, data.length);
        List<List<Bytes>> commands = new ArrayList<>();
        long validBytes = 0L;
        while (true) {
            ParseResult result = parser.next();
            switch (result) {
                case ParseResult.Command cmd -> {
                    commands.add(cmd.argv());
                    validBytes = data.length - (long) parser.bufferedBytes();
                }
                case ParseResult.NeedMore ignored -> {
                    boolean truncated = validBytes < data.length;
                    return new DecodeResult(commands, validBytes, truncated);
                }
                case ParseResult.ProtocolError err ->
                    throw new AofException("corrupt AOF record at offset " + validBytes + ": " + err.message());
            }
        }
    }
}
