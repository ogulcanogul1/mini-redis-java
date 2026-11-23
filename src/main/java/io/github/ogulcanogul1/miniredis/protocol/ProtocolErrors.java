package io.github.ogulcanogul1.miniredis.protocol;

/**
 * Redis's protocol error texts, in one place (K12). Command errors live in {@code commands.Errors};
 * these belong to the protocol layer, which the command layer does not know about. Texts are from
 * {@code learning/REDIS_REFERANS.md} §5.
 */
final class ProtocolErrors {

    static final String INVALID_MULTIBULK_LENGTH = "ERR Protocol error: invalid multibulk length";
    static final String INVALID_BULK_LENGTH = "ERR Protocol error: invalid bulk length";
    static final String UNBALANCED_QUOTES = "ERR Protocol error: unbalanced quotes in request";
    static final String TOO_BIG_INLINE = "ERR Protocol error: too big inline request";
    static final String TOO_BIG_MBULK_COUNT = "ERR Protocol error: too big mbulk count string";
    static final String TOO_BIG_BULK_COUNT = "ERR Protocol error: too big bulk count string";

    private ProtocolErrors() {}

    /** {@code ERR Protocol error: expected '$', got 'x'}. CR and LF are shown as a space, as in Redis. */
    static String expectedDollar(byte got) {
        char c = (char) Byte.toUnsignedInt(got); // ISO-8859-1: one byte, one char
        if (c == '\r' || c == '\n') {
            c = ' ';
        }
        return "ERR Protocol error: expected '$', got '" + c + "'";
    }
}
