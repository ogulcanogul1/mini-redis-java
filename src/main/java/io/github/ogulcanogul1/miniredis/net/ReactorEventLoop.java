package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded non-blocking NIO {@link Selector} event loop (Reactor pattern, K2).
 * Accepts newly handed-off {@link SocketChannel}s via a lock-free MPSC queue and isolates I/O and
 * unexpected runtime errors per connection so healthy connections never suffer.
 */
final class ReactorEventLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReactorEventLoop.class);
    private static final long MAX_IDLE_SELECT_MS = 250;

    private final String name;
    private final Dispatcher dispatcher;
    private final RespLimits limits;
    private final ServerStats stats;
    private final Clock clock;
    private final long maxOutputBytes;
    private final long idleTimeoutNanos;
    private final Runnable onConnectionClosed;
    private final Selector selector;
    private final ConcurrentLinkedQueue<SocketChannel> pendingRegistrations = new ConcurrentLinkedQueue<>();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(ClientSessionLoop.READ_BUFFER_SIZE);
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private Thread loopThread;
    private long nextClientId = 1;

    ReactorEventLoop(
            String name,
            Dispatcher dispatcher,
            RespLimits limits,
            ServerStats stats,
            Clock clock,
            long maxOutputBytes,
            long idleTimeoutNanos,
            Runnable onConnectionClosed)
            throws IOException {
        this.name = Objects.requireNonNull(name, "name");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be >= 1, got " + maxOutputBytes);
        }
        if (idleTimeoutNanos < 0) {
            throw new IllegalArgumentException("idleTimeoutNanos must be >= 0, got " + idleTimeoutNanos);
        }
        this.maxOutputBytes = maxOutputBytes;
        this.idleTimeoutNanos = idleTimeoutNanos;
        this.onConnectionClosed = Objects.requireNonNull(onConnectionClosed, "onConnectionClosed");
        this.selector = Selector.open();
    }

    void start() {
        synchronized (lifecycleLock) {
            if (loopThread != null) {
                throw new IllegalStateException("event loop already started: " + name);
            }
            running = true;
            loopThread = Thread.ofPlatform()
                    .name(name)
                    .uncaughtExceptionHandler((t, ex) -> log.error("uncaught error in {}", t.getName(), ex))
                    .start(this::runLoop);
        }
    }

    /** Enqueues an accepted channel to be registered on this loop's {@link Selector}. */
    void register(SocketChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (!running) {
            ClientSessionLoop.closeQuietly(channel);
            onConnectionClosed.run();
            return;
        }
        pendingRegistrations.add(channel);
        if (!running && pendingRegistrations.remove(channel)) {
            ClientSessionLoop.closeQuietly(channel);
            onConnectionClosed.run();
            return;
        }
        selector.wakeup();
    }

    /** Stops the event loop cleanly and waits up to {@value ClientSessionLoop#STOP_TIMEOUT_SECONDS}s. */
    @Override
    public void close() {
        Thread worker;
        synchronized (lifecycleLock) {
            running = false;
            worker = loopThread;
        }
        selector.wakeup();
        if (worker != null && Thread.currentThread() != worker) {
            try {
                if (!terminated.await(ClientSessionLoop.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("{} did not terminate within {}s", name, ClientSessionLoop.STOP_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (worker == null) {
            ClientSessionLoop.closeQuietly(selector);
            terminated.countDown();
        }
    }

    private void runLoop() {
        long selectTimeoutMs = computeSelectTimeoutMs();
        try {
            while (running) {
                try {
                    selector.select(selectTimeoutMs);
                } catch (IOException e) {
                    if (running) {
                        log.error("selector error in {}", name, e);
                    }
                    break;
                }
                if (!running) {
                    break;
                }
                drainRegistrations();
                processSelectedKeys();
                if (idleTimeoutNanos > 0) {
                    sweepIdleConnections();
                }
            }
        } finally {
            running = false;
            cleanupAll();
            terminated.countDown();
        }
    }

    private void drainRegistrations() {
        SocketChannel ch;
        while ((ch = pendingRegistrations.poll()) != null) {
            try {
                ch.configureBlocking(false);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                SelectionKey key = ch.register(selector, SelectionKey.OP_READ);
                long id = nextClientId++;
                NioConnection conn = new NioConnection(
                        id, ch, key, dispatcher, limits, maxOutputBytes, stats, clock, onConnectionClosed);
                key.attach(conn);
                stats.clientConnected();
                log.debug("client={} addr={} connected ({})", id, conn.remoteAddress(), name);
            } catch (IOException e) {
                log.debug("failed to register channel on {}: {}", name, e.getMessage());
                ClientSessionLoop.closeQuietly(ch);
                onConnectionClosed.run();
            }
        }
    }

    private void processSelectedKeys() {
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            handleSelectedKey(key);
        }
    }

    // Package-private to allow unit tests to verify per-connection error isolation cleanly.
    void handleSelectedKey(SelectionKey key) {
        NioConnection conn = null;
        try {
            conn = (NioConnection) key.attachment();
            if (!key.isValid()) {
                if (conn != null) {
                    conn.close();
                }
                return;
            }
            if (key.isReadable()) {
                conn.onReadable(readBuffer);
            }
            if (key.isValid() && key.isWritable()) {
                conn.onWritable();
            }
        } catch (CancelledKeyException e) {
            if (conn != null) {
                conn.close();
            }
        } catch (IOException e) {
            if (conn != null) {
                log.debug(
                        "client={} addr={} connection error: {}",
                        conn.clientId(),
                        conn.remoteAddress(),
                        e.getMessage());
                conn.close();
            }
        } catch (RuntimeException e) {
            if (conn != null) {
                log.error(
                        "unexpected error while handling client={} addr={}, closing connection",
                        conn.clientId(),
                        conn.remoteAddress(),
                        e);
                conn.close();
            } else {
                log.error("unexpected error on unregistered key in {}", name, e);
                key.cancel();
                ClientSessionLoop.closeQuietly(key.channel());
            }
        }
    }

    private void sweepIdleConnections() {
        long now = clock.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) {
                continue;
            }
            if (key.attachment() instanceof NioConnection conn && (now - conn.lastActiveNanos()) >= idleTimeoutNanos) {
                log.debug("client={} addr={} idle timeout reached, closing", conn.clientId(), conn.remoteAddress());
                conn.close();
            }
        }
    }

    private void cleanupAll() {
        SocketChannel ch;
        while ((ch = pendingRegistrations.poll()) != null) {
            ClientSessionLoop.closeQuietly(ch);
            onConnectionClosed.run();
        }
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof NioConnection conn) {
                conn.close();
            } else {
                key.cancel();
                ClientSessionLoop.closeQuietly(key.channel());
            }
        }
        ClientSessionLoop.closeQuietly(selector);
    }

    private long computeSelectTimeoutMs() {
        if (idleTimeoutNanos <= 0) {
            return 0;
        }
        long halfMs = TimeUnit.NANOSECONDS.toMillis(idleTimeoutNanos / 2);
        return Math.max(1L, Math.min(MAX_IDLE_SELECT_MS, halfMs));
    }
}
