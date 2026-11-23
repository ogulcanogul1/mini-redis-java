package io.github.ogulcanogul1.miniredis.util;

/** Default {@link Clock} implementation delegating to {@link System}. */
final class SystemClock implements Clock {

    static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {}

    @Override
    public long millis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
