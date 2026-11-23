package io.github.ogulcanogul1.miniredis.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PeriodicTaskTest {

    @Test
    void wrappedPeriodicTaskContinuesAfterException() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch threeRuns = new CountDownLatch(3);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.scheduleAtFixedRate(
                    PeriodicTask.wrap(
                            () -> {
                                int n = runs.incrementAndGet();
                                threeRuns.countDown();
                                if (n == 1) {
                                    throw new IllegalStateException("transient failure");
                                }
                            },
                            e -> errors.incrementAndGet()),
                    1,
                    10,
                    TimeUnit.MILLISECONDS);

            assertThat(threeRuns.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isEqualTo(1);
            assertThat(runs.get()).isGreaterThanOrEqualTo(3);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
