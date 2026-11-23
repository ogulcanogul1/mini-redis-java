package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocking single-client TCP backend ({@code io-model=single}, K2). Accepts one client at a time on
 * a dedicated platform thread and serves its requests sequentially until it disconnects, sends
 * {@code QUIT}, or triggers a protocol error.
 */
public final class SingleThreadServer implements ServerBackend {

    private static final Logger log = LoggerFactory.getLogger(SingleThreadServer.class);

    static final int READ_BUFFER_SIZE = ClientSessionLoop.READ_BUFFER_SIZE;

    private final String bindAddress;
    private final int configuredPort;
    private final Dispatcher dispatcher;
    private final RespLimits limits;
    private final ServerStats stats;
    private final Object lifecycleLock = new Object();
    private final Object clientLock = new Object();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private int boundPort = -1;
    private Thread acceptThread;
    private Socket currentClient;

    public SingleThreadServer(String bindAddress, int port, Dispatcher dispatcher, RespLimits limits) {
        this(bindAddress, port, dispatcher, limits, new ServerStats());
    }

    public SingleThreadServer(
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
                // Must be set before bind() so restarts can reuse a port still in TIME_WAIT.
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
                    .name("accept-single")
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
        Socket clientToClose;
        Thread worker;
        synchronized (lifecycleLock) {
            if (!running && acceptThread == null) {
                terminated.countDown();
                return;
            }
            running = false;
            toClose = serverSocket;
            worker = acceptThread;
        }
        synchronized (clientLock) {
            clientToClose = currentClient;
        }
        ClientSessionLoop.closeQuietly(clientToClose);
        ClientSessionLoop.closeQuietly(toClose);
        if (worker != null && Thread.currentThread() != worker) {
            try {
                if (!terminated.await(ClientSessionLoop.STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("accept-single did not terminate within {}s", ClientSessionLoop.STOP_TIMEOUT_SECONDS);
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
                synchronized (clientLock) {
                    if (!running) {
                        ClientSessionLoop.closeQuietly(client);
                        break;
                    }
                    currentClient = client;
                }
                try {
                    ClientSessionLoop.serve(nextClientId++, client, dispatcher, limits, stats, () -> running, log);
                } finally {
                    synchronized (clientLock) {
                        currentClient = null;
                    }
                }
            }
        } finally {
            running = false;
            ClientSessionLoop.closeQuietly(serverSocket);
            terminated.countDown();
        }
    }
}
