package io.github.ogulcanogul1.miniredis.server;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.util.Objects;

/**
 * Immutable server configuration, validated on construction (fail-fast, K12).
 *
 * @param bind address to bind; defaults to {@value #DEFAULT_BIND} so the server never exposes itself
 *     to the network unless explicitly configured
 * @param port TCP port (0 asks the OS for an ephemeral port); defaults to {@value #DEFAULT_PORT}
 * @param ioModel I/O backend strategy (K2)
 * @param logLevel server log level, mapped to Logback at startup (K12)
 * @param respLimits parser DoS limits
 */
public record ServerConfig(String bind, int port, IoModel ioModel, LogLevel logLevel, RespLimits respLimits) {

    /** Localhost only: safe default. */
    public static final String DEFAULT_BIND = "127.0.0.1";

    /** Mini-Redis default port (avoids clashing with a local Redis on 6379, K7). */
    public static final int DEFAULT_PORT = 6380;

    /** Supported I/O backend models (K2). Later phases add {@code thread-pool}, {@code virtual}, {@code multi-reactor}. */
    public enum IoModel {
        SINGLE
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
    }

    /** Returns the default configuration ({@code 127.0.0.1:6380}, {@code single}, {@code notice}). */
    public static ServerConfig defaults() {
        return new ServerConfig(DEFAULT_BIND, DEFAULT_PORT, IoModel.SINGLE, LogLevel.NOTICE, RespLimits.defaults());
    }

    /** Returns the default configuration listening on {@code port}. */
    public static ServerConfig onPort(int port) {
        return new ServerConfig(DEFAULT_BIND, port, IoModel.SINGLE, LogLevel.NOTICE, RespLimits.defaults());
    }
}
