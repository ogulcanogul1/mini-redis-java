package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.concurrency.NamedThreadFactory;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocking thread-per-connection backend ({@code io-model=thread-per-conn}, K2 intermediate step).
 * Spawns one dedicated platform thread per accepted TCP client.
 */
public final class ThreadPerConnServer implements ServerBackend {

    private static final Logger log = LoggerFactory.getLogger(ThreadPerConnServer.class);

    private final String bindAddress;
    private final int configuredPort;
    private final Dispatcher dispatcher;
    private final RespLimits limits;
    private final ServerStats stats;
    private final Object lifecycleLock = new Object();
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private final Phaser activeParties = new Phaser(1); // acceptor + active client threads
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final NamedThreadFactory clientThreads =
            new NamedThreadFactory("client", false, (t, ex) -> log.error("uncaught error in {}", t.getName(), ex));

    private volatile boolean running;
    private ServerSocket serverSocket;
    private int boundPort = -1;
    private Thread acceptThread;

    public ThreadPerConnServer(
            String bindAddress, int port, Dispatcher dispatcher, RespLimits limits, ServerStats stats) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.configuredPort = port;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.limits = Objects.requireNonNull(limits, "limits");
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
            this.acceptThread = Thread.ofPlatform()
                    .name("accept-tpc")
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
        synchronized (lifecycleLock) {
            if (!running && acceptThread == null) {
                terminated.countDown();
                return;
            }
            running = false;
            toClose = serverSocket;
            acceptor = acceptThread;
        }
        for (Socket client : activeClients) {
            ClientSessionLoop.closeQuietly(client);
        }
        ClientSessionLoop.closeQuietly(toClose);
        if (acceptor != null && Thread.currentThread() != acceptor) {
            try {
                if (!terminated.await(ClientSessionLoop.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn(
                            "thread-per-conn server did not terminate within {}s",
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
                activeParties.register();
                Thread t = clientThreads.newThread(() -> {
                    try {
                        ClientSessionLoop.serve(clientId, client, dispatcher, limits, stats, () -> running, log);
                    } finally {
                        activeClients.remove(client);
                        activeParties.arriveAndDeregister();
                    }
                });
                t.start();
            }
        } finally {
            running = false;
            ClientSessionLoop.closeQuietly(serverSocket);
            int phase = activeParties.arriveAndDeregister();
            if (phase >= 0) {
                activeParties.awaitAdvance(phase);
            }
            terminated.countDown();
        }
    }
}
