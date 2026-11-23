package io.github.ogulcanogul1.miniredis.protocol;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.Reply;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * One client's conversation, without the socket: bytes in, reply bytes out. Faz 3 connects it to a
 * socket; until then tests drive it directly. Not thread-safe: one session per connection.
 *
 * <p>Pipelining: a single {@link #onData} call may carry several requests, or the end of one and
 * the start of the next. Replies come out in request order. After a protocol error the session
 * writes the error, stops, and {@link #shouldClose()} turns {@code true}: Redis closes the
 * connection, because after bad framing it cannot know where the next request starts.
 */
public final class RespSession {

    private final RespParser parser;
    private final Dispatcher dispatcher;
    private boolean closed;

    public RespSession(Dispatcher dispatcher, RespLimits limits) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.parser = new RespParser(Objects.requireNonNull(limits, "limits"));
    }

    /**
     * Feeds {@code len} bytes of {@code src} from {@code off}, runs every request that is now
     * complete, and returns their replies (an empty array if none is complete yet).
     *
     * @throws IllegalStateException if the session is already closed
     * @throws IndexOutOfBoundsException if the range is not inside {@code src}
     */
    public byte[] onData(byte[] src, int off, int len) {
        if (closed) {
            throw new IllegalStateException("session is closed");
        }
        parser.feed(src, off, len);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            switch (parser.next()) {
                case ParseResult.Command command -> RespWriter.writeTo(dispatcher.dispatch(command.argv()), out);
                case ParseResult.NeedMore needMore -> {
                    return out.toByteArray();
                }
                case ParseResult.ProtocolError protocolError -> {
                    RespWriter.writeTo(Reply.error(protocolError.message()), out);
                    closed = true;
                    return out.toByteArray();
                }
            }
        }
    }

    /** Whether the connection must be closed (after a protocol error). */
    public boolean shouldClose() {
        return closed;
    }
}
