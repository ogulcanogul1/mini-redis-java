package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe server counters (PLAN §3 4.4, Faz 9 {@code INFO}): {@link LongAdder} for
 * high-contention per-command/error counts and {@link AtomicLong} for connection events.
 */
public final class ServerStats {

    private final Clock clock;
    private final long startTimeMillis;
    private final LongAdder totalCommands = new LongAdder();
    private final LongAdder totalConnectionsReceived = new LongAdder();
    private final LongAdder totalErrorReplies = new LongAdder();
    private final LongAdder rejectedClients = new LongAdder();
    private final AtomicLong connectedClients = new AtomicLong();
    private volatile boolean aofEnabled;
    private final AtomicLong rdbLastSaveEpochSeconds = new AtomicLong();
    private final LongAdder rdbChangesSinceLastSave = new LongAdder();

    public ServerStats() {
        this(Clock.system());
    }

    public ServerStats(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startTimeMillis = clock.millis();
    }

    public void recordCommand() {
        totalCommands.increment();
    }

    public long totalCommands() {
        return totalCommands.sum();
    }

    public void recordErrorReply() {
        totalErrorReplies.increment();
    }

    public long totalErrorReplies() {
        return totalErrorReplies.sum();
    }

    public void recordRejectedClient() {
        rejectedClients.increment();
    }

    public long rejectedClients() {
        return rejectedClients.sum();
    }

    public void clientConnected() {
        totalConnectionsReceived.increment();
        connectedClients.incrementAndGet();
    }

    public void clientDisconnected() {
        connectedClients.decrementAndGet();
    }

    public long connectedClients() {
        return connectedClients.get();
    }

    public long totalConnectionsReceived() {
        return totalConnectionsReceived.sum();
    }

    public long uptimeSeconds() {
        return Math.max(0L, (clock.millis() - startTimeMillis) / 1000L);
    }

    public void setAofEnabled(boolean enabled) {
        this.aofEnabled = enabled;
    }

    public boolean aofEnabled() {
        return aofEnabled;
    }

    public void recordWriteForRdb() {
        rdbChangesSinceLastSave.increment();
    }

    public long rdbChangesSinceLastSave() {
        return rdbChangesSinceLastSave.sum();
    }

    public void recordRdbSave(long epochSeconds) {
        rdbLastSaveEpochSeconds.set(epochSeconds);
        rdbChangesSinceLastSave.reset();
    }

    public long rdbLastSaveEpochSeconds() {
        return rdbLastSaveEpochSeconds.get();
    }
}
