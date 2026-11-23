package io.github.ogulcanogul1.miniredis.server;

import io.github.ogulcanogul1.miniredis.persistence.AofFsyncPolicy;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.EvictionPolicy;
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
 * @param ioThreads event loop count when {@code ioModel == MULTI_REACTOR} ({@code 1} = single reactor)
 * @param maxClients maximum simultaneous clients for {@code VIRTUAL} and {@code MULTI_REACTOR}
 * @param clientOutputBufferLimit maximum queued output bytes per client under NIO backpressure
 * @param idleTimeoutSeconds idle client timeout in seconds ({@code 0} disables timeout)
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
        LockType lockType,
        int ioThreads,
        int maxClients,
        long clientOutputBufferLimit,
        long idleTimeoutSeconds,
        boolean appendOnly,
        String appendFilename,
        AofFsyncPolicy appendFsync,
        long maxMemoryBytes,
        EvictionPolicy maxMemoryPolicy,
        String dbFilename,
        String requirePass,
        boolean protectedMode,
        String replicaOfHost,
        int replicaOfPort) {

    /** Localhost only: safe default. */
    public static final String DEFAULT_BIND = "127.0.0.1";

    /** Mini-Redis default port (avoids clashing with a local Redis on 6379, K7). */
    public static final int DEFAULT_PORT = 6380;

    public static final int DEFAULT_WORKER_THREADS = 8;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final int DEFAULT_SHARDS = Keyspaces.DEFAULT_SHARDS;
    public static final LockType DEFAULT_LOCK_TYPE = LockType.RWLOCK;
    public static final int DEFAULT_IO_THREADS = 4;
    public static final int DEFAULT_MAX_CLIENTS = 10_000;
    public static final long DEFAULT_CLIENT_OUTPUT_BUFFER_LIMIT = 16L * 1024 * 1024;
    public static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 0L;
    public static final boolean DEFAULT_APPEND_ONLY = false;
    public static final String DEFAULT_APPEND_FILENAME = "appendonly.aof";
    public static final AofFsyncPolicy DEFAULT_APPEND_FSYNC = AofFsyncPolicy.EVERYSEC;
    public static final long DEFAULT_MAX_MEMORY_BYTES = 0L;
    public static final EvictionPolicy DEFAULT_MAX_MEMORY_POLICY = EvictionPolicy.NOEVICTION;
    public static final String DEFAULT_DB_FILENAME = "dump.rdb";
    public static final String DEFAULT_REQUIRE_PASS = null;
    public static final boolean DEFAULT_PROTECTED_MODE = true;
    public static final String DEFAULT_REPLICA_OF_HOST = null;
    public static final int DEFAULT_REPLICA_OF_PORT = 0;

    /** Supported I/O backend models (K2). */
    public enum IoModel {
        SINGLE,
        THREAD_PER_CONN,
        THREAD_POOL,
        VIRTUAL,
        MULTI_REACTOR
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
        if (ioThreads < 1) {
            throw new ConfigException("io-threads must be >= 1, got " + ioThreads);
        }
        if (maxClients < 1) {
            throw new ConfigException("maxclients must be >= 1, got " + maxClients);
        }
        if (clientOutputBufferLimit < 1) {
            throw new ConfigException("client-output-buffer-limit must be >= 1, got " + clientOutputBufferLimit);
        }
        if (idleTimeoutSeconds < 0) {
            throw new ConfigException("idle-timeout-seconds must be >= 0, got " + idleTimeoutSeconds);
        }
        if (appendFilename == null || appendFilename.isBlank()) {
            throw new ConfigException("appendfilename must not be blank");
        }
        Objects.requireNonNull(appendFsync, "appendFsync");
        if (maxMemoryBytes < 0) {
            throw new ConfigException("maxmemory must be >= 0, got " + maxMemoryBytes);
        }
        Objects.requireNonNull(maxMemoryPolicy, "maxMemoryPolicy");
        if (dbFilename == null || dbFilename.isBlank()) {
            throw new ConfigException("dbfilename must not be blank");
        }
        if (requirePass != null && requirePass.isEmpty()) {
            requirePass = null;
        }
        if (replicaOfHost != null && replicaOfHost.isBlank()) {
            throw new ConfigException("replicaof host must not be blank");
        }
        if (replicaOfHost != null && (replicaOfPort < 1 || replicaOfPort > 65535)) {
            throw new ConfigException("replicaof port must be between 1 and 65535, got " + replicaOfPort);
        }
    }

    /** Backward-compatible 5-argument constructor using default pool, sharding, and reactor settings. */
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

    /** Backward-compatible 9-argument constructor using default reactor and client-limit settings. */
    public ServerConfig(
            String bind,
            int port,
            IoModel ioModel,
            LogLevel logLevel,
            RespLimits respLimits,
            int workerThreads,
            int queueCapacity,
            int shards,
            LockType lockType) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                workerThreads,
                queueCapacity,
                shards,
                lockType,
                DEFAULT_IO_THREADS,
                DEFAULT_MAX_CLIENTS,
                DEFAULT_CLIENT_OUTPUT_BUFFER_LIMIT,
                DEFAULT_IDLE_TIMEOUT_SECONDS);
    }

    /** Backward-compatible 13-argument constructor using default AOF settings ({@code appendonly=false}). */
    public ServerConfig(
            String bind,
            int port,
            IoModel ioModel,
            LogLevel logLevel,
            RespLimits respLimits,
            int workerThreads,
            int queueCapacity,
            int shards,
            LockType lockType,
            int ioThreads,
            int maxClients,
            long clientOutputBufferLimit,
            long idleTimeoutSeconds) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                workerThreads,
                queueCapacity,
                shards,
                lockType,
                ioThreads,
                maxClients,
                clientOutputBufferLimit,
                idleTimeoutSeconds,
                DEFAULT_APPEND_ONLY,
                DEFAULT_APPEND_FILENAME,
                DEFAULT_APPEND_FSYNC);
    }

    /** Backward-compatible 16-argument constructor using default unlimited memory ({@code maxmemory=0}). */
    public ServerConfig(
            String bind,
            int port,
            IoModel ioModel,
            LogLevel logLevel,
            RespLimits respLimits,
            int workerThreads,
            int queueCapacity,
            int shards,
            LockType lockType,
            int ioThreads,
            int maxClients,
            long clientOutputBufferLimit,
            long idleTimeoutSeconds,
            boolean appendOnly,
            String appendFilename,
            AofFsyncPolicy appendFsync) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                workerThreads,
                queueCapacity,
                shards,
                lockType,
                ioThreads,
                maxClients,
                clientOutputBufferLimit,
                idleTimeoutSeconds,
                appendOnly,
                appendFilename,
                appendFsync,
                DEFAULT_MAX_MEMORY_BYTES,
                DEFAULT_MAX_MEMORY_POLICY,
                DEFAULT_DB_FILENAME);
    }

    /** Backward-compatible 18-argument constructor using default {@value #DEFAULT_DB_FILENAME}. */
    public ServerConfig(
            String bind,
            int port,
            IoModel ioModel,
            LogLevel logLevel,
            RespLimits respLimits,
            int workerThreads,
            int queueCapacity,
            int shards,
            LockType lockType,
            int ioThreads,
            int maxClients,
            long clientOutputBufferLimit,
            long idleTimeoutSeconds,
            boolean appendOnly,
            String appendFilename,
            AofFsyncPolicy appendFsync,
            long maxMemoryBytes,
            EvictionPolicy maxMemoryPolicy) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                workerThreads,
                queueCapacity,
                shards,
                lockType,
                ioThreads,
                maxClients,
                clientOutputBufferLimit,
                idleTimeoutSeconds,
                appendOnly,
                appendFilename,
                appendFsync,
                maxMemoryBytes,
                maxMemoryPolicy,
                DEFAULT_DB_FILENAME,
                DEFAULT_REQUIRE_PASS,
                DEFAULT_PROTECTED_MODE);
    }

    /** Backward-compatible 19-argument constructor using default {@code requirePass=null} and {@code protectedMode=true}. */
    public ServerConfig(
            String bind,
            int port,
            IoModel ioModel,
            LogLevel logLevel,
            RespLimits respLimits,
            int workerThreads,
            int queueCapacity,
            int shards,
            LockType lockType,
            int ioThreads,
            int maxClients,
            long clientOutputBufferLimit,
            long idleTimeoutSeconds,
            boolean appendOnly,
            String appendFilename,
            AofFsyncPolicy appendFsync,
            long maxMemoryBytes,
            EvictionPolicy maxMemoryPolicy,
            String dbFilename) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                workerThreads,
                queueCapacity,
                shards,
                lockType,
                ioThreads,
                maxClients,
                clientOutputBufferLimit,
                idleTimeoutSeconds,
                appendOnly,
                appendFilename,
                appendFsync,
                maxMemoryBytes,
                maxMemoryPolicy,
                dbFilename,
                DEFAULT_REQUIRE_PASS,
                DEFAULT_PROTECTED_MODE,
                DEFAULT_REPLICA_OF_HOST,
                DEFAULT_REPLICA_OF_PORT);
    }

    /** Backward-compatible 21-argument constructor with no {@code replicaof}. */
    public ServerConfig(
            String bind,
            int port,
            IoModel ioModel,
            LogLevel logLevel,
            RespLimits respLimits,
            int workerThreads,
            int queueCapacity,
            int shards,
            LockType lockType,
            int ioThreads,
            int maxClients,
            long clientOutputBufferLimit,
            long idleTimeoutSeconds,
            boolean appendOnly,
            String appendFilename,
            AofFsyncPolicy appendFsync,
            long maxMemoryBytes,
            EvictionPolicy maxMemoryPolicy,
            String dbFilename,
            String requirePass,
            boolean protectedMode) {
        this(
                bind,
                port,
                ioModel,
                logLevel,
                respLimits,
                workerThreads,
                queueCapacity,
                shards,
                lockType,
                ioThreads,
                maxClients,
                clientOutputBufferLimit,
                idleTimeoutSeconds,
                appendOnly,
                appendFilename,
                appendFsync,
                maxMemoryBytes,
                maxMemoryPolicy,
                dbFilename,
                requirePass,
                protectedMode,
                DEFAULT_REPLICA_OF_HOST,
                DEFAULT_REPLICA_OF_PORT);
    }

    /** Returns the default configuration ({@code 127.0.0.1:6380}, {@code multi-reactor}, {@code 4} I/O threads, {@code 16} rwlock shards). */
    public static ServerConfig defaults() {
        return onPort(DEFAULT_PORT);
    }

    /** Returns the default configuration listening on {@code port}. */
    public static ServerConfig onPort(int port) {
        return new ServerConfig(
                DEFAULT_BIND,
                port,
                IoModel.MULTI_REACTOR,
                LogLevel.NOTICE,
                RespLimits.defaults(),
                DEFAULT_WORKER_THREADS,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_SHARDS,
                DEFAULT_LOCK_TYPE,
                DEFAULT_IO_THREADS,
                DEFAULT_MAX_CLIENTS,
                DEFAULT_CLIENT_OUTPUT_BUFFER_LIMIT,
                DEFAULT_IDLE_TIMEOUT_SECONDS,
                DEFAULT_APPEND_ONLY,
                DEFAULT_APPEND_FILENAME,
                DEFAULT_APPEND_FSYNC,
                DEFAULT_MAX_MEMORY_BYTES,
                DEFAULT_MAX_MEMORY_POLICY,
                DEFAULT_DB_FILENAME,
                DEFAULT_REQUIRE_PASS,
                DEFAULT_PROTECTED_MODE,
                DEFAULT_REPLICA_OF_HOST,
                DEFAULT_REPLICA_OF_PORT);
    }
}
