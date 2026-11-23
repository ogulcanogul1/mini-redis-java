package io.github.ogulcanogul1.miniredis.protocol;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits an inline command line into arguments, like Redis's {@code sdssplitargs}
 * ({@code learning/REDIS_REFERANS.md} §5).
 *
 * <ul>
 *   <li>Arguments are separated by whitespace (space, tab, CR, LF, vertical tab, form feed).
 *   <li>{@code "..."}: {@code \xHH} (two hex digits), {@code \n}, {@code \r}, {@code \t}, {@code \b},
 *       {@code \a}; any other escaped byte stands for itself ({@code \"}, {@code \\}, {@code \q} →
 *       {@code q}).
 *   <li>{@code '...'}: only {@code \'} is an escape.
 *   <li>A quote may open in the middle of a word ({@code a"b"} → {@code ab}). A closing quote must be
 *       followed by whitespace or the end of the line.
 * </ul>
 */
final class InlineArgs {

    private InlineArgs() {}

    /**
     * Splits {@code line[from..to)}, the line without its line break.
     *
     * @return the arguments (empty for a blank line), or {@link Optional#empty()} if a quote is not
     *     closed or a closing quote is followed by something other than whitespace
     */
    static Optional<List<Bytes>> split(byte[] line, int from, int to) {
        List<Bytes> args = new ArrayList<>();
        byte[] word = new byte[to - from]; // an argument is never longer than the line
        int p = from;
        while (true) {
            while (p < to && isSpace(line[p])) {
                p++;
            }
            if (p == to) {
                return Optional.of(args);
            }
            int n = 0;
            boolean inDouble = false;
            boolean inSingle = false;
            boolean done = false;
            while (!done) {
                if (inDouble) {
                    if (p == to) {
                        return Optional.empty(); // unterminated "
                    }
                    byte c = line[p];
                    if (c == '\\' && p + 3 < to && line[p + 1] == 'x' && isHex(line[p + 2]) && isHex(line[p + 3])) {
                        word[n++] = (byte) (hex(line[p + 2]) * 16 + hex(line[p + 3]));
                        p += 3;
                    } else if (c == '\\' && p + 1 < to) {
                        p++;
                        word[n++] = unescape(line[p]);
                    } else if (c == '"') {
                        if (p + 1 < to && !isSpace(line[p + 1])) {
                            return Optional.empty(); // "ab"c
                        }
                        done = true;
                    } else {
                        word[n++] = c;
                    }
                } else if (inSingle) {
                    if (p == to) {
                        return Optional.empty(); // unterminated '
                    }
                    byte c = line[p];
                    if (c == '\\' && p + 1 < to && line[p + 1] == '\'') {
                        p++;
                        word[n++] = '\'';
                    } else if (c == '\'') {
                        if (p + 1 < to && !isSpace(line[p + 1])) {
                            return Optional.empty();
                        }
                        done = true;
                    } else {
                        word[n++] = c;
                    }
                } else if (p == to) {
                    done = true;
                } else {
                    byte c = line[p];
                    switch (c) {
                        // Only these end an unquoted word; \v and \f do not (same as Redis).
                        case ' ', '\n', '\r', '\t' -> done = true;
                        case '"' -> inDouble = true;
                        case '\'' -> inSingle = true;
                        default -> word[n++] = c;
                    }
                }
                if (p < to) {
                    p++;
                }
            }
            args.add(Bytes.of(word, 0, n));
        }
    }

    private static byte unescape(byte c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'a' -> 7; // BEL; Java has no '\a'
            default -> c;
        };
    }

    /** C's {@code isspace} in the "C" locale. */
    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
    }

    private static boolean isHex(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(byte c) {
        if (c <= '9') {
            return c - '0';
        }
        return (c | 0x20) - 'a' + 10;
    }
}
