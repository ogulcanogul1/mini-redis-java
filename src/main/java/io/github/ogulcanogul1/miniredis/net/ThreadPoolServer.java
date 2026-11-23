package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.concurrency.NamedThreadFactory;
import io.github.ogulcanogul1.miniredis.concurrency.RejectionPolicy;
import io.github.ogulcanogul1.miniredis.concurrency.ThreadPool;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocking thread-pool TCP backend ({@code io-model=thread-pool}, K2). The acceptor puts each
 * client connection into a bounded queue consumed by a fixed {@link ThreadPool} of worker threads.
 */
public final class ThreadPoolServer implements ServerBackend {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolServer.class);

    private final String bindAddress;
    private final int configuredPort;
    private final Dispatcher dispatcher;
    private final RespLimits limits;
    private final int workerThreads;
    private final int queueCapacity;
    private final ServerStats stats;
    private final Object lifecycleLock = new Object();
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private int boundPort = -1;
    private Thread acceptThread;
    private ThreadPool pool;

    public ThreadPoolServer(
            String bindAddress,
            int port,
            Dispatcher dispatcher,
            RespLimits limits,
            int workerThreads,
            int queueCapacity,
            ServerStats stats) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1, got " + workerThreads);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got " + queueCapacity);
        }
        this.configuredPort = port;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    @Override
    public void start() throws IOException {
        synchronized (lifecycleLock) {
            if (acceptThread != null) {
                throw new IllegalStateException("server already started");
            }
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), configuredPort));
            } catch (IOException e) {
                ClientSessionLoop.closeQuietly(socket);
                throw e;
            }
            this.serverSocket = socket;
            this.boundPort = socket.getLocalPort();
            this.running = true;
            this.pool = new ThreadPool(
                    workerThreads,
                    queueCapacity,
                    new NamedThreadFactory(
                            "io-worker", false, (t, ex) -> log.error("uncaught error in {}", t.getName(), ex)),
                    RejectionPolicy.BLOCK,
                    ex -> log.error("uncaught error in client worker", ex));
            this.acceptThread = Thread.ofPlatform()
                    .name("accept-pool")
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
        ServerSocket toClose;
        Thread acceptor;
        ThreadPool workerPool;
        synchronized (lifecycleLock) {
            if (!running && acceptThread == null) {
                terminated.countDown();
                return;
            }
            running = false;
            toClose = serverSocket;
            acceptor = acceptThread;
            workerPool = pool;
        }
        for (Socket client : activeClients) {
            ClientSessionLoop.closeQuietly(client);
        }
        if (workerPool != null) {
            List<Runnable> dropped = workerPool.shutdownNow();
            if (!dropped.isEmpty()) {
                log.debug("dropped {} queued client tasks on shutdown", dropped.size());
            }
        }
        ClientSessionLoop.closeQuietly(toClose);
        if (acceptor != null && Thread.currentThread() != acceptor) {
            try {
                if (!terminated.await(ClientSessionLoop.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("thread-pool server did not terminate within {}s", ClientSessionLoop.STOP_TIMEOUT_SECONDS);
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
        long nextClientId = 1;
        try {
            while (running) {
                Socket client;
                try {
                    client = serverSocket.accept();
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
                long clientId = nextClientId++;
                activeClients.add(client);
                if (!running) {
                    activeClients.remove(client);
                    ClientSessionLoop.closeQuietly(client);
                    break;
                }
                try {
                    pool.execute(() -> {
                        try {
                            ClientSessionLoop.serve(clientId, client, dispatcher, limits, stats, () -> running, log);
                        } finally {
                            activeClients.remove(client);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    activeClients.remove(client);
                    ClientSessionLoop.closeQuietly(client);
                }
            }
        } finally {
            running = false;
            ClientSessionLoop.closeQuietly(serverSocket);
            if (pool != null) {
                try {
                    pool.shutdown();
                    pool.awaitTermination(ClientSessionLoop.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (Socket client : activeClients) {
                ClientSessionLoop.closeQuietly(client);
            }
            terminated.countDown();
        }
    }
}
