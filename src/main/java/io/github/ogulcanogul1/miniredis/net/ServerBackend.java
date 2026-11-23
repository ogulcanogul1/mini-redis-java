package io.github.ogulcanogul1.miniredis.net;

import java.io.IOException;

/**
 * Network server abstraction for the pluggable I/O models (K2). Faz 3 implements {@link
 * SingleThreadServer}; later phases add thread-pool, virtual threads, and NIO multi-reactor.
 */
public interface ServerBackend extends AutoCloseable {

    /** Binds the listening socket and starts accepting client connections. */
    void start() throws IOException;

    /** Blocks until the server has fully stopped after {@link #stop()}. */
    void awaitTermination() throws InterruptedException;

    /** Signals the server to stop accepting and closes open connections. Idempotent. */
    void stop();

    /** Returns the bound TCP port, or {@code -1} if the backend has not started. */
    int port();

    @Override
    default void close() {
        stop();
    }
}
