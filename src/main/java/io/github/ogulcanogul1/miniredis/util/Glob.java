package io.github.ogulcanogul1.miniredis.util;

import java.util.Objects;

/**
 * Redis-style glob matching on raw bytes (binary-safe).
 *
 * <p>Supported: {@code *} (any sequence, also empty), {@code ?} (any one byte), {@code [abc]},
 * {@code [a-z]} (a reversed range such as {@code [z-a]} works too), {@code [^a]} (negation) and
 * {@code \} (the next byte is literal; a trailing {@code \} matches a backslash). An unclosed
 * {@code [} runs to the end of the pattern. Byte values are compared unsigned.
 * package io.github.ogulcanogul1.miniredis.util;
 *
 * import java.util.Objects;
 *
 * /**
 * Redis-style glob matching on raw bytes (binary-safe).
 *
 * <p>Supported: {@code *} (any sequence, also empty), {@code ?} (any one byte), {@code [abc]},
 * {@code [a-z]} (a reversed range such as {@code [z-a]} works too), {@code [^a]} (negation) and
 * {@code \} (the next byte is literal; a trailing {@code \} matches a backslash). An unclosed
 * {@code [} runs to the end of the pattern. Byte values are compared unsigned.
 *
 * <p>Runs in O(pattern × text) time even with many {@code *}: when a match fails, it backtracks only
 * to the most recent {@code *}, never further.
 */
public final class Glob {

    private static final int NO_MATCH = -1;

    private Glob() {}

    /** Returns whether {@code text} matches {@code pattern}. */
    public static boolean matches(byte[] pattern, byte[] text) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(text, "text");
        int p = 0;
        int t = 0;
        int starP = -1; // pattern index just after the most recent '*'
        int starT = -1; // text index that '*' was last tried to stop at
        while (t < text.length) {
            if (p < pattern.length) {
                if (pattern[p] == '*') {
                    while (p < pattern.length && pattern[p] == '*') {
                        p++;
                    }
                    if (p == pattern.length) {
                        return true; // a trailing '*' matches the rest
                    }
                    starP = p;
                    starT = t;
                    continue;
                }
                int next = matchOne(pattern, p, text[t]);
                if (next != NO_MATCH) {
                    p = next;
                    t++;
                    continue;
                }
            }
            if (starP < 0) {
                return false;
            }
            // Let the last '*' swallow one more byte and retry from there.
            starT++;
            t = starT;
            p = starP;
        }
        while (p < pattern.length && pattern[p] == '*') {
            p++;
        }
        return p == pattern.length;
    }

    /**
     * Tries the single token that starts at {@code pattern[p]} (not {@code *}) against byte {@code
     * b}. Returns the index just after the token if it matches, otherwise {@link #NO_MATCH}.
     */
    private static int matchOne(byte[] pattern, int p, byte b) {
        byte c = pattern[p];
        if (c == '?') {
            return p + 1;
        }
        if (c == '[') {
            return matchClass(pattern, p + 1, b);
        }
        if (c == '\\' && p + 1 < pattern.length) {
            return pattern[p + 1] == b ? p + 2 : NO_MATCH;
        }
        return c == b ? p + 1 : NO_MATCH;
    }

    /** {@code p} points just after {@code '['}. */
    private static int matchClass(byte[] pattern, int p, byte b) {
        boolean negate = p < pattern.length && pattern[p] == '^';
        if (negate) {
            p++;
        }
        int value = Byte.toUnsignedInt(b);
        boolean matched = false;
        while (p < pattern.length) {
            byte c = pattern[p];
            if (c == '\\' && p + 1 < pattern.length) {
                matched |= pattern[p + 1] == b;
                p += 2;
            } else if (c == ']') {
                p++;
                break;
            } else if (p + 2 < pattern.length && pattern[p + 1] == '-') {
                int lo = Byte.toUnsignedInt(c);
                int hi = Byte.toUnsignedInt(pattern[p + 2]);
                if (lo > hi) {
                    int tmp = lo;
                    lo = hi;
                    hi = tmp;
                }
                matched |= value >= lo && value <= hi;
                p += 3;
            } else {
                matched |= c == b;
                p++;
            }
        }
        return matched != negate ? p : NO_MATCH;
    }
}
