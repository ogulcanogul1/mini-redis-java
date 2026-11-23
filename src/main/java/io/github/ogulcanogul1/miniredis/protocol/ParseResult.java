package io.github.ogulcanogul1.miniredis.protocol;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;
import java.util.Objects;

/**
 * What {@link RespParser#next()} found in the bytes fed so far. Bad input is a value here, not an
 * exception (K12): the network layer decides what happens to the connection.
 */
public sealed interface ParseResult {

    /** The buffered bytes do not hold a complete request yet. */
    ParseResult NEED_MORE = new NeedMore();

    /**
     * A complete request: the command name followed by its arguments.
     *
     * @throws IllegalArgumentException if {@code argv} is empty
     */
    record Command(List<Bytes> argv) implements ParseResult {
        public Command {
            argv = List.copyOf(argv);
            if (argv.isEmpty()) {
                throw new IllegalArgumentException("argv must not be empty");
            }
        }
    }

    /** Wait for more bytes. Use {@link ParseResult#NEED_MORE}. */
    record NeedMore() implements ParseResult {}

    /**
     * The input breaks the protocol. {@code message} is the error text without the leading {@code
     * -}, e.g. {@code ERR Protocol error: invalid bulk length}. Redis sends it and closes the
     * connection.
     */
    record ProtocolError(String message) implements ParseResult {
        public ProtocolError {
            Objects.requireNonNull(message, "message");
        }
    }
}
