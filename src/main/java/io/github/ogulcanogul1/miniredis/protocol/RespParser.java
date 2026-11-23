package io.github.ogulcanogul1.miniredis.protocol;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Incremental RESP2 request parser, written as a state machine. Not thread-safe: one parser per
 * connection.
 *
 * <p>Bytes arrive in pieces of any size ({@link #feed}); TCP keeps their order but not their
 * boundaries. {@link #next()} returns the next complete request, {@link ParseResult#NEED_MORE} if
 * the buffered bytes do not hold one yet, or a {@link ParseResult.ProtocolError}. The parser keeps
 * its position between calls, so a request split across many reads is never scanned from the start
 * again, and a bulk argument is copied out of the buffer exactly once, when all of it has arrived.
 *
 * <p>A request is either a RESP array of bulk strings ({@code *2\r\n$3\r\nGET\r\n$1\r\nk\r\n}) or, if
 * the first byte is not {@code *}, an inline command ({@code GET k\r\n}). Behaviour follows Redis
 * 7.0 ({@code learning/REDIS_REFERANS.md} §5), including two checks Redis does not make: the two
 * bytes after a bulk and the byte after a header's {@code \r} are skipped without looking at them.
 *
 * <p>After a protocol error the parser is finished: {@link #next()} returns the same error again and
 * {@link #feed} throws.
 */
public final class RespParser {

    static final int INITIAL_CAPACITY = 16 * 1024;

    /** A drained buffer larger than this is replaced by a small one, so one big request does not pin memory. */
    static final int MAX_IDLE_CAPACITY = 64 * 1024;

    /** Never reserve room for more arguments than this up front, whatever {@code *<n>} announces. */
    static final int MAX_PREALLOCATED_ARGS = 1024;

    /** The largest array the JVM reliably creates. */
    static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private enum State {
        /** Between requests: the next byte starts a {@code *<n>} header or an inline line. */
        REQUEST,
        /** Inside an array: waiting for a {@code $<len>} header. */
        BULK_HEADER,
        /** Inside an array: waiting for {@code bulkLength} bytes and the CRLF after them. */
        BULK_DATA,
        /** A protocol error was found. Terminal. */
        FAILED
    }

    private final RespLimits limits;
    private byte[] buf = new byte[INITIAL_CAPACITY];
    private int start; // first unread byte
    private int end; // one past the last buffered byte
    private State state = State.REQUEST;
    private int remainingArgs; // BULK_HEADER, BULK_DATA: bulks still missing in the current array
    private int bulkLength; // BULK_DATA: length of the bulk being read
    private List<Bytes> args; // BULK_HEADER, BULK_DATA: bulks read so far
    private ParseResult.ProtocolError error; // FAILED

    /** Creates a parser with {@link RespLimits#defaults()}. */
    public RespParser() {
        this(RespLimits.defaults());
    }

    public RespParser(RespLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Appends {@code len} bytes of {@code src}, starting at {@code off}, to the buffer. The bytes are
     * copied; {@code src} can be reused right after the call.
     *
     * @throws IllegalStateException after a protocol error
     * @throws IndexOutOfBoundsException if the range is not inside {@code src}
     */
    public void feed(byte[] src, int off, int len) {
        Objects.requireNonNull(src, "src");
        Objects.checkFromIndexSize(off, len, src.length);
        if (state == State.FAILED) {
            throw new IllegalStateException("parser already failed: " + error.message());
        }
        makeRoom(len);
        System.arraycopy(src, off, buf, end, len);
        end += len;
    }

    /** Returns the next complete request, {@link ParseResult#NEED_MORE}, or a protocol error. */
    public ParseResult next() {
        while (true) {
            ParseResult result = switch (state) {
                case REQUEST -> readRequestStart();
                case BULK_HEADER -> readBulkHeader();
                case BULK_DATA -> readBulkData();
                case FAILED -> error;
            };
            // null: some bytes were consumed and the state moved on, so try the next step.
            if (result != null) {
                if (start == end) {
                    releaseBuffer();
                }
                return result;
            }
        }
    }

    private ParseResult readRequestStart() {
        if (start == end) {
            return ParseResult.NEED_MORE;
        }
        return buf[start] == '*' ? readArrayHeader() : readInline();
    }

    private ParseResult readArrayHeader() {
        int cr = indexOf((byte) '\r', start);
        if (cr < 0) {
            return end - start > limits.maxLineLength()
                    ? fail(ProtocolErrors.TOO_BIG_MBULK_COUNT)
                    : ParseResult.NEED_MORE;
        }
        if (cr + 1 >= end) {
            return ParseResult.NEED_MORE; // the byte after '\r' has not arrived yet
        }
        OptionalLong count = Numbers.parseLong(buf, start + 1, cr);
        if (count.isEmpty() || count.getAsLong() > limits.maxArrayLength()) {
            return fail(ProtocolErrors.INVALID_MULTIBULK_LENGTH);
        }
        start = cr + 2;
        long n = count.getAsLong();
        if (n <= 0) {
            return null; // *0 and *-1 are empty requests: Redis ignores them
        }
        remainingArgs = (int) n;
        args = new ArrayList<>((int) Math.min(n, MAX_PREALLOCATED_ARGS));
        state = State.BULK_HEADER;
        return null;
    }

    private ParseResult readBulkHeader() {
        int cr = indexOf((byte) '\r', start);
        if (cr < 0) {
            return end - start > limits.maxLineLength()
                    ? fail(ProtocolErrors.TOO_BIG_BULK_COUNT)
                    : ParseResult.NEED_MORE;
        }
        if (cr + 1 >= end) {
            return ParseResult.NEED_MORE;
        }
        if (buf[start] != '$') {
            return fail(ProtocolErrors.expectedDollar(buf[start]));
        }
        OptionalLong length = Numbers.parseLong(buf, start + 1, cr);
        if (length.isEmpty() || length.getAsLong() < 0 || length.getAsLong() > limits.maxBulkLength()) {
            return fail(ProtocolErrors.INVALID_BULK_LENGTH);
        }
        start = cr + 2;
        bulkLength = (int) length.getAsLong();
        state = State.BULK_DATA;
        return null;
    }

    private ParseResult readBulkData() {
        if ((long) end - start < (long) bulkLength + 2) {
            return ParseResult.NEED_MORE;
        }
        args.add(Bytes.of(buf, start, bulkLength));
        start += bulkLength + 2; // the CRLF after the data is skipped unchecked, as in Redis
        remainingArgs--;
        if (remainingArgs > 0) {
            state = State.BULK_HEADER;
            return null;
        }
        ParseResult command = new ParseResult.Command(args);
        args = null;
        state = State.REQUEST;
        return command;
    }

    private ParseResult readInline() {
        int lf = indexOf((byte) '\n', start);
        if (lf < 0) {
            return end - start > limits.maxLineLength() ? fail(ProtocolErrors.TOO_BIG_INLINE) : ParseResult.NEED_MORE;
        }
        int lineEnd = lf > start && buf[lf - 1] == '\r' ? lf - 1 : lf;
        Optional<List<Bytes>> argv = InlineArgs.split(buf, start, lineEnd);
        start = lf + 1;
        if (argv.isEmpty()) {
            return fail(ProtocolErrors.UNBALANCED_QUOTES);
        }
        if (argv.get().isEmpty()) {
            return null; // a blank line is ignored
        }
        return new ParseResult.Command(argv.get());
    }

    private ParseResult fail(String message) {
        state = State.FAILED;
        error = new ParseResult.ProtocolError(message);
        args = null;
        return error;
    }

    private int indexOf(byte b, int from) {
        for (int i = from; i < end; i++) {
            if (buf[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** Makes sure {@code len} more bytes fit after {@code end}: first by moving the unread bytes to the front, then by growing. */
    private void makeRoom(int len) {
        if (buf.length - end >= len) {
            return;
        }
        int unread = end - start;
        long needed = (long) unread + len;
        if (needed > MAX_CAPACITY) {
            // Cannot happen with the default limits when next() runs after every feed (RespSession
            // does): the buffer then holds one unfinished request (at most ~512 MB) plus one read.
            // A bug or an extreme config, not client input: an exception, not a ProtocolError.
            throw new IllegalStateException("read buffer would exceed " + MAX_CAPACITY + " bytes");
        }
        byte[] target = buf;
        if (needed > buf.length) {
            target = new byte[(int) Math.min(Math.max(2L * buf.length, needed), MAX_CAPACITY)];
        }
        System.arraycopy(buf, start, target, 0, unread);
        buf = target;
        start = 0;
        end = unread;
    }

    private void releaseBuffer() {
        start = 0;
        end = 0;
        if (buf.length > MAX_IDLE_CAPACITY) {
            buf = new byte[INITIAL_CAPACITY];
        }
    }

    /** Current buffer size. Package-private, for tests. */
    int capacity() {
        return buf.length;
    }
}
