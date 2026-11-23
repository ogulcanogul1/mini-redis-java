package io.github.ogulcanogul1.miniredis.server;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.util.Objects;

/**
 * Immutable server configuration, validated on construction (fail-fast, K12).
 *
 * @param bind address to bind; defaults to {@value #DEFAULT_BIND}
 * @param port TCP port (0 asks the OS for an ephemeral port); defaults to {@value #DEFAULT_PORT}
 * @param ioModel I/O backend strategy (K2)
 * @param logLevel server log level, mapped to Logback at startup (K12)
 * @param respLimits parser DoS limits
 * @param workerThreads worker count when {@code ioModel == THREAD_POOL}
 * @param queueCapacity connection queue capacity when {@code ioModel == THREAD_POOL}
 * @param shards keyspace shard count (positive power of two, K3)
 * @param lockType keyspace per-shard lock strategy (K3)
 */
public record ServerConfig(
        String bind,
        int port,
        IoModel ioModel,
        LogLevel logLevel,
        RespLimits respLimits,
        int workerThreads,
        int queueCapacity,
        int shards,
        LockType lockType) {

    /** Localhost only: safe default. */
    public static final String DEFAULT_BIND = "127.0.0.1";

    /** Mini-Redis default port (avoids clashing with a local Redis on 6379, K7). */
    public static final int DEFAULT_PORT = 6380;

    public static final int DEFAULT_WORKER_THREADS = 8;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final int DEFAULT_SHARDS = Keyspaces.DEFAULT_SHARDS;
    public static final LockType DEFAULT_LOCK_TYPE = LockType.RWLOCK;

    /** Supported I/O backend models (K2). */
    public enum IoModel {
        SINGLE,
        THREAD_PER_CONN,
        THREAD_POOL
    }

    /** Redis-style log levels (K12). */
    public enum LogLevel {
        DEBUG,
        VERBOSE,
        NOTICE,
        WARNING
    }

    public ServerConfig {
        if (bind == null || bind.isBlank()) {
            throw new ConfigException("bind must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new ConfigException("port must be between 0 and 65535, got " + port);
        }
        Objects.requireNonNull(ioModel, "ioModel");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(respLimits, "respLimits");
        if (workerThreads < 1) {
            throw new ConfigException("worker-threads must be >= 1, got " + workerThreads);
        }
        if (queueCapacity < 1) {
            throw new ConfigException("queue-capacity must be >= 1, got " + queueCapacity);
        }
        if (shards < 1 || (shards & (shards - 1)) != 0) {
            throw new ConfigException("shards must be a positive power of two, got " + shards);
        }
        Objects.requireNonNull(lockType, "lockType");
    }

    /** Backward-compatible 5-argument constructor using default pool and sharding settings. */
    public ServerConfig(String bind, int port, IoModel ioModel, LogLevel logLevel, RespLimits respLimits) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                DEFAULT_WORKER_THREADS,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_SHARDS,
                DEFAULT_LOCK_TYPE);
    }

    /** Returns the default configuration ({@code 127.0.0.1:6380}, {@code thread-pool}, {@code 16} rwlock shards). */
    public static ServerConfig defaults() {
        return onPort(DEFAULT_PORT);
    }

    /** Returns the default configuration listening on {@code port}. */
    public static ServerConfig onPort(int port) {
        return new ServerConfig(
                DEFAULT_BIND,
                port,
                IoModel.THREAD_POOL,
                LogLevel.NOTICE,
                RespLimits.defaults(),
                DEFAULT_WORKER_THREADS,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_SHARDS,
                DEFAULT_LOCK_TYPE);
    }
}
