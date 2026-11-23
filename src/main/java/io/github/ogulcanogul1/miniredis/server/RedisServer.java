package io.github.ogulcanogul1.miniredis.server;

import ch.qos.logback.classic.Level;
import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.net.ServerBackend;
import io.github.ogulcanogul1.miniredis.net.SingleThreadServer;
import io.github.ogulcanogul1.miniredis.net.ThreadPerConnServer;
import io.github.ogulcanogul1.miniredis.net.ThreadPoolServer;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root (PLAN §2.3): wires sharded storage, stats, commands, protocol session, and the
 * configured network backend, and owns server lifecycle logging.
 */
public final class RedisServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisServer.class);

    private final ServerConfig config;
    private final ServerStats stats;
    private final ServerBackend backend;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public RedisServer(ServerConfig config) {
        this(config, CommandTable.standard());
    }

    public RedisServer(ServerConfig config, CommandTable table) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(table, "table");
        this.stats = new ServerStats();
        Keyspace keyspace = Keyspaces.sharded(config.shards(), config.lockType());
        Dispatcher dispatcher = new Dispatcher(table, new CommandContext(keyspace, stats));
        this.backend = switch (config.ioModel()) {
            case SINGLE -> new SingleThreadServer(config.bind(), config.port(), dispatcher, config.respLimits(), stats);
            case THREAD_PER_CONN ->
                new ThreadPerConnServer(config.bind(), config.port(), dispatcher, config.respLimits(), stats);
            case THREAD_POOL ->
                new ThreadPoolServer(
                        config.bind(),
                        config.port(),
                        dispatcher,
                        config.respLimits(),
                        config.workerThreads(),
                        config.queueCapacity(),
                        stats);
        };
    }

    /**
     * Returns the project version, read from {@code version.properties} which Maven fills in through
     * resource filtering.
     *
     * @throws IllegalStateException if the resource or the key is missing
     */
    public static String version() {
        try (InputStream in = RedisServer.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                throw new IllegalStateException("version.properties not found on the classpath");
            }
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("version.properties has no 'version' key");
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Configures logging, binds the listening socket, and starts serving requests. */
    public void start() throws IOException {
        applyLogLevel(config.logLevel());
        backend.start();
        started.set(true);
        log.info(
                "mini-redis {} listening on {}:{} (io-model={}, shards={}, lock-type={})",
                version(),
                config.bind(),
                backend.port(),
                config.ioModel().name().toLowerCase(Locale.ROOT),
                config.shards(),
                config.lockType().name().toLowerCase(Locale.ROOT));
    }

    /** Blocks until {@link #stop()} completes. */
    public void awaitTermination() throws InterruptedException {
        backend.awaitTermination();
    }

    /** Stops the network backend cleanly. Idempotent. */
    public void stop() {
        backend.stop();
        if (started.get() && stopped.compareAndSet(false, true)) {
            log.info("mini-redis stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Returns the bound TCP port, or {@code -1} before {@link #start()}. */
    public int port() {
        return backend.port();
    }

    /** Returns shared live server counters. */
    public ServerStats stats() {
        return stats;
    }

    private static void applyLogLevel(ServerConfig.LogLevel logLevel) {
        if (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
            root.setLevel(
                    switch (logLevel) {
                        case DEBUG, VERBOSE -> Level.DEBUG;
                        case NOTICE -> Level.INFO;
                        case WARNING -> Level.WARN;
                    });
        }
    }
}
