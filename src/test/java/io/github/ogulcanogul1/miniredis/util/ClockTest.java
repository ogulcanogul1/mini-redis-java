package io.github.ogulcanogul1.miniredis.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClockTest {

    @Test
    void systemClockReturnsWallTimeAndMonotonicNanoTime() {
        Clock clock = Clock.system();
        assertThat(Clock.system()).isSameAs(clock);

        long beforeMillis = System.currentTimeMillis();
        long wall = clock.millis();
        long afterMillis = System.currentTimeMillis();
        assertThat(wall).isBetween(beforeMillis, afterMillis);

        long t1 = clock.nanoTime();
        long t2 = clock.nanoTime();
        assertThat(t2 - t1).isGreaterThanOrEqualTo(0L);
    }
}
