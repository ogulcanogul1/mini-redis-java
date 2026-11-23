package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Every Redis error text, in one place (K12). The texts follow Redis 7.0 and were checked against
 * a real server. Tests compare against the literal strings, not against this class.
 */
final class Errors {

    static final Reply SYNTAX = Reply.error("ERR syntax error");

    /** Redis shows at most this many bytes of the command name, and of all arguments together. */
    static final int MAX_SHOWN_BYTES = 128;

    private Errors() {}

    /** {@code ERR wrong number of arguments for 'get' command}. {@code name} is the table name. */
    static Reply wrongArity(String name) {
        return Reply.error("ERR wrong number of arguments for '" + name + "' command");
    }

    /**
     * {@code ERR unknown command 'foo', with args beginning with: 'a' 'b' }. The name is shown as the
     * client sent it. Every argument is quoted and followed by a space, so the text ends with a
     * space.
     *
     * <p>As in Redis 7.0: the name is cut to {@value #MAX_SHOWN_BYTES} bytes. Arguments are added
     * while the argument text is shorter than {@value #MAX_SHOWN_BYTES} bytes, each cut to the room
     * that is left. Every CR and LF becomes a space, so the reply stays on one line. Unlike Redis, a
     * zero byte does not end an argument (Redis uses C strings here; we stay binary-safe).
     */
    static Reply unknownCommand(List<Bytes> argv) {
        StringBuilder args = new StringBuilder();
        for (int i = 1; i < argv.size() && args.length() < MAX_SHOWN_BYTES; i++) {
            int room = MAX_SHOWN_BYTES - args.length(); // measured before the opening quote, as in Redis
            args.append('\'').append(text(argv.get(i), room)).append("' ");
        }
        String message = "ERR unknown command '" + text(argv.getFirst(), MAX_SHOWN_BYTES)
                + "', with args beginning with: " + args;
        return Reply.error(message.replace('\r', ' ').replace('\n', ' '));
    }

    // ISO-8859-1 keeps every byte as one char, so a char count is a byte count. The RESP writer
    // encodes error texts back with ISO-8859-1, so the client sees the bytes it sent.
    private static String text(Bytes b, int maxBytes) {
        byte[] data = b.toByteArray();
        return new String(data, 0, Math.min(data.length, maxBytes), StandardCharsets.ISO_8859_1);
    }
}
