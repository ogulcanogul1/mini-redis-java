package io.github.ogulcanogul1.miniredis.commands;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe server counters (PLAN §3 4.4): {@link LongAdder} for high-contention per-command
 * counts and {@link AtomicLong} for infrequent connection connect/disconnect events.
 */
public final class ServerStats {

    private final LongAdder totalCommands = new LongAdder();
    private final AtomicLong connectedClients = new AtomicLong();

    public void recordCommand() {
        totalCommands.increment();
    }

    public long totalCommands() {
        return totalCommands.sum();
    }

    public void clientConnected() {
        connectedClients.incrementAndGet();
    }

    public void clientDisconnected() {
        connectedClients.decrementAndGet();
    }

    public long connectedClients() {
        return connectedClients.get();
    }
}
