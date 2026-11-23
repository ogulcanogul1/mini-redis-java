package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking NIO multi-reactor TCP backend ({@code io-model=multi-reactor}, K2). One acceptor
 * thread accepts connections via {@link ServerSocketChannel} and distributes them round-robin across
 * {@code ioThreads} {@link ReactorEventLoop} workers (with {@code ioThreads=1} matching Redis's
 * single-threaded event loop).
 */
public final class MultiReactorServer implements ServerBackend {

    private static final Logger log = LoggerFactory.getLogger(MultiReactorServer.class);

    private final String bindAddress;
    private final int configuredPort;
    private final Dispatcher dispatcher;
    private final RespLimits limits;
    private final int ioThreads;
    private final int maxClients;
    private final long clientOutputBufferLimit;
    private final long idleTimeoutNanos;
    private final Clock clock;
    private final ServerStats stats;
    private final Object lifecycleLock = new Object();
    private final AtomicInteger activeClientCount = new AtomicInteger();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile boolean running;
    private ServerSocketChannel serverChannel;
    private int boundPort = -1;
    private Thread acceptThread;
    private ReactorEventLoop[] loops;

    public MultiReactorServer(
            String bindAddress,
            int port,
            Dispatcher dispatcher,
            RespLimits limits,
            int ioThreads,
            int maxClients,
            long clientOutputBufferLimit,
            long idleTimeoutSeconds,
            Clock clock,
            ServerStats stats) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (ioThreads < 1) {
            throw new IllegalArgumentException("ioThreads must be >= 1, got " + ioThreads);
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("maxClients must be >= 1, got " + maxClients);
        }
        if (clientOutputBufferLimit < 1) {
            throw new IllegalArgumentException("clientOutputBufferLimit must be >= 1, got " + clientOutputBufferLimit);
        }
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must be >= 0, got " + idleTimeoutSeconds);
        }
        this.configuredPort = port;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ioThreads = ioThreads;
        this.maxClients = maxClients;
        this.clientOutputBufferLimit = clientOutputBufferLimit;
        this.idleTimeoutNanos = TimeUnit.SECONDS.toNanos(idleTimeoutSeconds);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    @Override
    public void start() throws IOException {
        synchronized (lifecycleLock) {
            if (acceptThread != null) {
                throw new IllegalStateException("server already started");
            }
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ReactorEventLoop[] created = new ReactorEventLoop[ioThreads];
            try {
                ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                ssc.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), configuredPort));
                for (int i = 0; i < ioThreads; i++) {
                    created[i] = new ReactorEventLoop(
                            "io-reactor-" + (i + 1),
                            dispatcher,
                            limits,
                            stats,
                            clock,
                            clientOutputBufferLimit,
                            idleTimeoutNanos,
                            activeClientCount::decrementAndGet);
                }
            } catch (IOException e) {
                ClientSessionLoop.closeQuietly(ssc);
                for (ReactorEventLoop loop : created) {
                    if (loop != null) {
                        loop.close();
                    }
                }
                throw e;
            }
            this.serverChannel = ssc;
            this.boundPort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            this.loops = created;
            this.running = true;
            for (ReactorEventLoop loop : created) {
                loop.start();
            }
            this.acceptThread = Thread.ofPlatform()
                    .name("accept-reactor")
                    .uncaughtExceptionHandler((t, ex) -> log.error("uncaught error in {}", t.getName(), ex))
                    .start(this::acceptLoop);
        }
    }

    @Override
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    @Override
    public void stop() {
        ServerSocketChannel toClose;
        Thread acceptor;
        synchronized (lifecycleLock) {
            if (!running && acceptThread == null) {
                terminated.countDown();
                return;
            }
            running = false;
            toClose = serverChannel;
            acceptor = acceptThread;
        }
        ClientSessionLoop.closeQuietly(toClose);
        if (acceptor != null && Thread.currentThread() != acceptor) {
            try {
                if (!terminated.await(ClientSessionLoop.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn(
                            "multi-reactor server did not terminate within {}s",
                            ClientSessionLoop.STOP_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public int port() {
        return boundPort;
    }

    private void acceptLoop() {
        long nextLoop = 0;
        try {
            while (running) {
                SocketChannel client;
                try {
                    client = serverChannel.accept();
                } catch (IOException e) {
                    if (running) {
                        log.debug("accept failed: {}", e.getMessage());
                    }
                    break;
                }
                if (!running) {
                    ClientSessionLoop.closeQuietly(client);
                    break;
                }
                if (activeClientCount.incrementAndGet() > maxClients) {
                    activeClientCount.decrementAndGet();
                    log.warn("maxclients ({}) reached, closing connection", maxClients);
                    ClientSessionLoop.closeQuietly(client);
                    continue;
                }
                ReactorEventLoop target = loops[(int) (nextLoop++ % loops.length)];
                target.register(client);
            }
        } finally {
            running = false;
            ClientSessionLoop.closeQuietly(serverChannel);
            if (loops != null) {
                for (ReactorEventLoop loop : loops) {
                    if (loop != null) {
                        loop.close();
                    }
                }
            }
            terminated.countDown();
        }
    }
}
