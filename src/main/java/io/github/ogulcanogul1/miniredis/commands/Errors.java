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

    private Errors() {}

    /* {@code ERR wrong number of arguments for 'get' command}. {@code name} is the table name. */

    /** {@code ERR wrong number of arguments for 'get' command}. {@code name} is the table name. */
    static Reply wrongArity(String name) {
        return Reply.error("ERR wrong number of arguments for '" + name + "' command");
    }

    /**
     * {@code ERR unknown command 'foo', with args beginning with: 'a' 'b' }. The name is shown as the
     * client sent it. Every argument is quoted and followed by a space, so the text ends with a
     * space.
     */
    static Reply unknownCommand(List<Bytes> argv) {
        StringBuilder sb = new StringBuilder("ERR unknown command '")
                .append(text(argv.getFirst()))
                .append("', with args beginning with: ");
        for (Bytes arg : argv.subList(1, argv.size())) {
            sb.append('\'').append(text(arg)).append("' ");
        }
        return Reply.error(sb.toString());
    }

    // ISO-8859-1 keeps every byte as one char. Faz 2's RESP writer encodes error and simple
    // strings back with ISO-8859-1, so the client sees the bytes it sent.
    private static String text(Bytes b) {
        return b.decode(StandardCharsets.ISO_8859_1);
    }
}
