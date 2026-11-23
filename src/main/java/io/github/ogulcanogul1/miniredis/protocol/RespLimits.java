package io.github.ogulcanogul1.miniredis.protocol;

/**
 * Size limits the parser enforces, so a client cannot make the server wait for or buffer an
 * arbitrarily large request. The defaults are Redis 7.0's. Faz 3 reads them from the config.
 *
 * @param maxArrayLength the largest element count a {@code *<n>} header may announce
 * @param maxBulkLength the largest length a {@code $<n>} header may announce ({@code
 *     proto-max-bulk-len} in Redis)
 * @param maxLineLength how many bytes the parser waits for the end of a header or inline line
 *     before giving up ({@code PROTO_INLINE_MAX_SIZE} in Redis)
 */
public record RespLimits(int maxArrayLength, int maxBulkLength, int maxLineLength) {

    /** Redis accepts {@code *<n>} up to {@code INT_MAX}. */
    public static final int DEFAULT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE;

    /** 512 MB, Redis's default {@code proto-max-bulk-len}. */
    public static final int DEFAULT_MAX_BULK_LENGTH = 512 * 1024 * 1024;

    /** 64 KB, Redis's {@code PROTO_INLINE_MAX_SIZE}. */
    public static final int DEFAULT_MAX_LINE_LENGTH = 64 * 1024;

    /**
     * The largest {@code maxBulkLength} allowed: a bulk, its header and its CRLF must fit in one Java
     * array, which holds a little less than {@code Integer.MAX_VALUE} bytes.
     */
    public static final int MAX_BULK_LENGTH_CAP = Integer.MAX_VALUE - 1024;

    /**
     * @throws IllegalArgumentException if a limit is not positive or {@code maxBulkLength} is above
     *     {@link #MAX_BULK_LENGTH_CAP}
     */
    public RespLimits {
        if (maxArrayLength <= 0 || maxBulkLength <= 0 || maxLineLength <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        if (maxBulkLength > MAX_BULK_LENGTH_CAP) {
            throw new IllegalArgumentException("maxBulkLength must be at most " + MAX_BULK_LENGTH_CAP);
        }
    }

    /** Redis 7.0's limits. */
    public static RespLimits defaults() {
        return new RespLimits(DEFAULT_MAX_ARRAY_LENGTH, DEFAULT_MAX_BULK_LENGTH, DEFAULT_MAX_LINE_LENGTH);
    }
}
