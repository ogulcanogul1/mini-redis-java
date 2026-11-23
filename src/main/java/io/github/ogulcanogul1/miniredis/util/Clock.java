package io.github.ogulcanogul1.miniredis.util;

/**
 * Time source abstraction (K8): wall-clock {@link #millis()} for persisted expiration timestamps and
 * monotonic {@link #nanoTime()} for elapsed durations such as idle timeouts and budgets.
 */
public interface Clock {

    /** Returns current wall-clock time in milliseconds since the Unix epoch. */
    long millis();

    /** Returns monotonic time in nanoseconds suitable only for measuring elapsed durations. */
    long nanoTime();

    /** Returns the default system clock backed by {@link System}. */
    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
