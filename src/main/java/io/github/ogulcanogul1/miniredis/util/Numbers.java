package io.github.ogulcanogul1.miniredis.util;

import java.util.Objects;
import java.util.OptionalLong;

/** Strict parsing of decimal numbers written as ASCII bytes, with the rules of Redis's {@code string2ll}. */
public final class Numbers {

    /** "-9223372036854775808" has 20 characters; nothing longer can be a {@code long}. */
    private static final int MAX_LONG_DIGITS = 20;

    private static final long LIMIT = Long.MIN_VALUE / 10; // -922337203685477580

    private Numbers() {}

    /**
     * Parses {@code b[from..to)} as a {@code long}.
     *
     * <p>Accepted: an optional {@code -}, then digits, with no leading zero (only {@code "0"} itself
     * may start with 0). Rejected: an empty range, {@code +}, spaces, {@code "-0"}, leading zeros,
     * any other byte, and values outside the {@code long} range.
     *
     * @return the value, or {@link OptionalLong#empty()} if the range is not a valid number
     * @throws IndexOutOfBoundsException if the range is not inside {@code b}
     */
    public static OptionalLong parseLong(byte[] b, int from, int to) {
        Objects.requireNonNull(b, "b");
        Objects.checkFromToIndex(from, to, b.length);
        int len = to - from;
        if (len == 0 || len > MAX_LONG_DIGITS) {
            return OptionalLong.empty();
        }
        if (len == 1 && b[from] == '0') {
            return OptionalLong.of(0);
        }
        int p = from;
        boolean negative = b[p] == '-';
        if (negative) {
            p++;
        }
        if (p == to || b[p] < '1' || b[p] > '9') {
            return OptionalLong.empty();
        }
        // Accumulate as a negative number: the negative range is one larger, so Long.MIN_VALUE fits.
        long v = 0;
        for (; p < to; p++) {
            int d = b[p] - '0';
            if (d < 0 || d > 9) {
                return OptionalLong.empty();
            }
            if (v < LIMIT || (v == LIMIT && d > 8)) {
                return OptionalLong.empty(); // v * 10 - d would go below Long.MIN_VALUE
            }
            v = v * 10 - d;
        }
        if (negative) {
            return OptionalLong.of(v);
        }
        return v == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(-v);
    }
}
