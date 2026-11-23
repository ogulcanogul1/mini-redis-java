package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.protocol.RespSession;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-connection NIO state owned exclusively by one {@link ReactorEventLoop} thread (not thread-safe).
 * Handles partial reads, non-blocking partial writes via {@code OP_WRITE}, output buffer backpressure,
 * and closing after flushing the final reply on {@code QUIT} or protocol error.
 */
final class NioConnection {

    private static final Logger log = LoggerFactory.getLogger(NioConnection.class);

    private final long clientId;
    private final SocketChannel channel;
    private final SelectionKey key;
    private final RespSession session;
    private final Deque<ByteBuffer> outbound = new ArrayDeque<>();
    private final long maxOutputBytes;
    private final ServerStats stats;
    private final Clock clock;
    private final Runnable onClose;
    private final String remoteAddress;

    private long queuedOutputBytes;
    private long lastActiveNanos;
    private boolean closeAfterFlush;
    private boolean closed;

    NioConnection(
            long clientId,
            SocketChannel channel,
            SelectionKey key,
            Dispatcher dispatcher,
            RespLimits limits,
            long maxOutputBytes,
            ServerStats stats,
            Clock clock,
            Runnable onClose) {
        this(clientId, channel, key, dispatcher, limits, maxOutputBytes, stats, clock, onClose, task -> {});
    }

    NioConnection(
            long clientId,
            SocketChannel channel,
            SelectionKey key,
            Dispatcher dispatcher,
            RespLimits limits,
            long maxOutputBytes,
            ServerStats stats,
            Clock clock,
            Runnable onClose,
            Consumer<Runnable> submitTask) {
        this.clientId = clientId;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.key = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(submitTask, "submitTask");
        boolean loopback;
        try {
            loopback = ClientSessionLoop.isLoopback(channel.getRemoteAddress());
        } catch (IOException e) {
            loopback = true;
        }
        this.session =
                new RespSession(dispatcher, limits, loopback, bytes -> submitTask.accept(() -> enqueueAsync(bytes)));
        this.maxOutputBytes = maxOutputBytes;
        this.stats = Objects.requireNonNull(stats, "stats");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
        this.remoteAddress = safeRemoteAddress(channel);
        this.lastActiveNanos = clock.nanoTime();
    }

    long clientId() {
        return clientId;
    }

    long lastActiveNanos() {
        return lastActiveNanos;
    }

    String remoteAddress() {
        return remoteAddress;
    }

    /** Reads available bytes, executes complete requests, and flushes/queues replies. */
    void onReadable(ByteBuffer readBuf) throws IOException {
        if (closed) {
            return;
        }
        readBuf.clear();
        int n = channel.read(readBuf);
        if (n < 0) {
            close();
            return;
        }
        if (n == 0) {
            return;
        }
        lastActiveNanos = clock.nanoTime();
        byte[] reply = session.onData(readBuf.array(), 0, n);
        if (reply.length > 0) {
            if (reply.length > maxOutputBytes - queuedOutputBytes) {
                log.warn(
                        "client={} addr={} exceeded output buffer limit {} bytes, closing",
                        clientId,
                        remoteAddress,
                        maxOutputBytes);
                close();
                return;
            }
            outbound.addLast(ByteBuffer.wrap(reply));
            queuedOutputBytes += reply.length;
        }
        if (session.shouldClose()) {
            closeAfterFlush = true;
        }
        flushOutbound();
    }

    /** Drains queued outbound buffers when the socket becomes writable. */
    void onWritable() throws IOException {
        if (closed) {
            return;
        }
        flushOutbound();
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        session.close();
        outbound.clear();
        queuedOutputBytes = 0;
        key.cancel();
        ClientSessionLoop.closeQuietly(channel);
        stats.clientDisconnected();
        onClose.run();
        log.debug("client={} addr={} closed", clientId, remoteAddress);
    }

    private void enqueueAsync(byte[] bytes) {
        if (closed || bytes.length == 0) {
            return;
        }
        if (bytes.length > maxOutputBytes - queuedOutputBytes) {
            log.warn(
                    "client={} addr={} exceeded output buffer limit {} bytes, closing",
                    clientId,
                    remoteAddress,
                    maxOutputBytes);
            close();
            return;
        }
        outbound.addLast(ByteBuffer.wrap(bytes));
        queuedOutputBytes += bytes.length;
        try {
            flushOutbound();
        } catch (IOException e) {
            close();
        }
    }

    private void flushOutbound() throws IOException {
        while (!outbound.isEmpty()) {
            ByteBuffer head = outbound.peekFirst();
            int before = head.remaining();
            channel.write(head);
            int written = before - head.remaining();
            if (written > 0) {
                queuedOutputBytes -= written;
                lastActiveNanos = clock.nanoTime();
            }
            if (head.hasRemaining()) {
                break;
            }
            outbound.pollFirst();
        }
        if (outbound.isEmpty()) {
            if (closeAfterFlush) {
                close();
            } else {
                key.interestOps(SelectionKey.OP_READ);
            }
        } else {
            int ops = SelectionKey.OP_WRITE | (closeAfterFlush ? 0 : SelectionKey.OP_READ);
            key.interestOps(ops);
        }
    }

    private static String safeRemoteAddress(SocketChannel ch) {
        try {
            return String.valueOf(ch.getRemoteAddress());
        } catch (IOException e) {
            return "unknown";
        }
    }
}
