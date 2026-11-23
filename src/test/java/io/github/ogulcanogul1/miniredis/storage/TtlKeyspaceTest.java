package io.github.ogulcanogul1.miniredis.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TtlKeyspaceTest {

    private static final class FakeClock implements Clock {
        final AtomicLong millis = new AtomicLong(10_000L);
        final AtomicLong nanos = new AtomicLong(1_000_000L);

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        void advanceMillis(long delta) {
            millis.addAndGet(delta);
            nanos.addAndGet(delta * 1_000_000L);
        }
    }

    @Test
    void lazyExpireHidesKeyUnderReadWithoutDeletingUntilWriteOrPurge() {
        FakeClock clock = new FakeClock();
        Keyspace ks = Keyspaces.inMemory(clock);
        Bytes k = Bytes.ascii("session");
        assertThat(ks.set(k, Bytes.ascii("v1"), SetCondition.ALWAYS, 500L)).isTrue();
        assertThat(ks.pttl(k)).isEqualTo(500L);

        clock.advanceMillis(500L);
        assertThat(ks.get(k)).isEmpty();
        assertThat(ks.exists(k)).isFalse();
        assertThat(ks.pttl(k)).isEqualTo(-2L);
        assertThat(ks.mget(List.of(k))).containsExactly(java.util.Optional.empty());

        List<Bytes> seen = new ArrayList<>();
        ks.forEachKey(seen::add);
        assertThat(seen).isEmpty();

        // K6: read lock did not delete the entry; write or active purge removes it.
        assertThat(ks.size()).isEqualTo(1);
        assertThat(ks.purgeExpiredSample(20, 10_000_000L)).isEqualTo(1);
        assertThat(ks.size()).isEqualTo(0);
    }

    @Test
    void incrPreservesTtlAndPlainSetClearsTtl() {
        FakeClock clock = new FakeClock();
        Keyspace ks = Keyspaces.inMemory(clock);
        Bytes k = Bytes.ascii("cnt");

        ks.set(k, Bytes.ascii("10"), SetCondition.ALWAYS, 1_000L);
        assertThat(ks.incrBy(k, 5)).isEqualTo(new IncrResult.Ok(15));
        assertThat(ks.pttl(k)).isEqualTo(1_000L);

        ks.set(k, Bytes.ascii("20"));
        assertThat(ks.pttl(k)).isEqualTo(-1L);
    }

    @Test
    void nxXxConditionsAndRenameTtlTransfer() {
        FakeClock clock = new FakeClock();
        Keyspace ks = Keyspaces.inMemory(clock);
        Bytes a = Bytes.ascii("a");
        Bytes b = Bytes.ascii("b");

        assertThat(ks.set(a, Bytes.ascii("1"), SetCondition.IF_PRESENT, null)).isFalse();
        assertThat(ks.set(a, Bytes.ascii("1"), SetCondition.IF_ABSENT, 200L)).isTrue();
        assertThat(ks.set(a, Bytes.ascii("2"), SetCondition.IF_ABSENT, null)).isFalse();

        clock.advanceMillis(250L);
        // Expired key counts as absent for NX!
        assertThat(ks.set(a, Bytes.ascii("3"), SetCondition.IF_ABSENT, 1_000L)).isTrue();
        assertThat(ks.rename(a, b)).isTrue();
        assertThat(ks.pttl(b)).isEqualTo(1_000L);
        assertThat(ks.persist(b)).isTrue();
        assertThat(ks.pttl(b)).isEqualTo(-1L);
        assertThat(ks.persist(b)).isFalse();
    }

    @Test
    void expireOverflowAndNonPositiveTtl() {
        FakeClock clock = new FakeClock();
        Keyspace ks = Keyspaces.inMemory(clock);
        Bytes k = Bytes.ascii("k");
        ks.set(k, Bytes.ascii("v"));

        assertThatThrownBy(() -> ks.expire(k, Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ks.expire(k, 0L)).isTrue();
        assertThat(ks.get(k)).isEmpty();
    }

    @Test
    void activePurgeRepeatsWhenOverQuarterExpiredAndStopsAtBudget() {
        FakeClock clock = new FakeClock();
        Keyspace ks = Keyspaces.inMemory(clock);
        for (int i = 0; i < 40; i++) {
            ks.set(Bytes.ascii("exp:" + i), Bytes.ascii("v"), SetCondition.ALWAYS, 100L);
        }
        clock.advanceMillis(100L);
        int removed = ks.purgeExpiredSample(5, 1_000_000_000L);
        assertThat(removed).isEqualTo(40);
        assertThat(ks.size()).isEqualTo(0);
    }
}
