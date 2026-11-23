package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.concurrency.NamedThreadFactory;
import io.github.ogulcanogul1.miniredis.concurrency.PeriodicTask;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic active-expiration background task (K6). Samples expiring keys from each shard within a
 * fixed time budget on a dedicated daemon scheduler.
 */
public final class ActiveExpirer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ActiveExpirer.class);

    public static final long DEFAULT_INTERVAL_MS = 100;
    public static final int DEFAULT_SAMPLE_SIZE = 20;
    public static final long DEFAULT_TIME_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

    private final Keyspace keyspace;
    private final long intervalMs;
    private final int sampleSizePerShard;
    private final long timeBudgetNanos;
    private final ScheduledExecutorService scheduler;

    public ActiveExpirer(Keyspace keyspace) {
        this(keyspace, DEFAULT_INTERVAL_MS, DEFAULT_SAMPLE_SIZE, DEFAULT_TIME_BUDGET_NANOS);
    }

    public ActiveExpirer(Keyspace keyspace, long intervalMs, int sampleSizePerShard, long timeBudgetNanos) {
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        if (intervalMs < 1) {
            throw new IllegalArgumentException("intervalMs must be positive, got " + intervalMs);
        }
        if (sampleSizePerShard < 1) {
            throw new IllegalArgumentException("sampleSizePerShard must be positive, got " + sampleSizePerShard);
        }
        if (timeBudgetNanos < 0) {
            throw new IllegalArgumentException("timeBudgetNanos must be non-negative, got " + timeBudgetNanos);
        }
        this.intervalMs = intervalMs;
        this.sampleSizePerShard = sampleSizePerShard;
        this.timeBudgetNanos = timeBudgetNanos;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("active-expire"));
    }

    /** Starts periodic active expiration. Safe to call once. */
    public void start() {
        scheduler.scheduleAtFixedRate(
                PeriodicTask.wrap(this::sweepOnce, e -> log.error("periodic task 'active-expire' failed", e)),
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS);
    }

    /** Runs a single active-expiration cycle and returns the number of removed keys. */
    public int sweepOnce() {
        return keyspace.purgeExpiredSample(sampleSizePerShard, timeBudgetNanos);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
