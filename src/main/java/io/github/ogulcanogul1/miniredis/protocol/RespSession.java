package io.github.ogulcanogul1.miniredis.protocol;

import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.Reply;
import io.github.ogulcanogul1.miniredis.commands.SessionAuthState;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * One client's conversation, without the socket: bytes in, reply bytes out. Faz 3 connects it to a
 * socket; until then tests drive it directly. Not thread-safe: one session per connection.
 *
 * <p>Pipelining: a single {@link #onData} call may carry several requests, or the end of one and
 * the start of the next. Replies come out in request order. After a protocol error or a {@code
 * QUIT} command, the session writes the reply, stops, and {@link #shouldClose()} turns {@code
 * true}: Redis closes the connection immediately.
 */
public final class RespSession {

    private final RespParser parser;
    private final Dispatcher dispatcher;
    private final SessionAuthState authState;
    private boolean closed;

    public RespSession(Dispatcher dispatcher, RespLimits limits) {
        this(dispatcher, limits, true);
    }

    public RespSession(Dispatcher dispatcher, RespLimits limits, boolean loopback) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.parser = new RespParser(Objects.requireNonNull(limits, "limits"));
        this.authState = dispatcher.newSessionState(loopback);
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
                case ParseResult.Command command -> {
                    RespWriter.writeTo(dispatcher.dispatch(command.argv(), authState), out);
                    if (CommandTable.isQuit(command.argv())) {
                        closed = true;
                        return out.toByteArray();
                    }
                }
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

    /** Whether the connection must be closed (after a protocol error or {@code QUIT}). */
    public boolean shouldClose() {
        return closed;
    }
}
