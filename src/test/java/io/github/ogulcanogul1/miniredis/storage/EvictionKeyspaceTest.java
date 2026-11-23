package io.github.ogulcanogul1.miniredis.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EvictionKeyspaceTest {

    @Test
    void allkeysLruEvictsLeastRecentlyUsedEntries() {
        AtomicLong millis = new AtomicLong(1_000L);
        Clock clock = new Clock() {
            @Override
            public long millis() {
                return millis.get();
            }

            @Override
            public long nanoTime() {
                return millis.get() * 1_000_000L;
            }
        };
        Keyspace ks = Keyspaces.sharded(1, LockType.RWLOCK, clock);
        ks.set(Bytes.ascii("old"), Bytes.ascii("value-1"));
        millis.set(2_000L);
        ks.set(Bytes.ascii("mid"), Bytes.ascii("value-2"));
        millis.set(3_000L);
        ks.set(Bytes.ascii("new"), Bytes.ascii("value-3"));

        // Touch "old" so "mid" becomes the least recently used.
        millis.set(4_000L);
        assertThat(ks.get(Bytes.ascii("old"))).isPresent();

        long twoEntriesBudget = ks.usedMemoryBytes() - 1;
        int evicted = ks.evictIfNeeded(twoEntriesBudget, EvictionPolicy.ALLKEYS_LRU, 5);
        assertThat(evicted).isGreaterThanOrEqualTo(1);
        assertThat(ks.exists(Bytes.ascii("mid"))).isFalse();
        assertThat(ks.exists(Bytes.ascii("old"))).isTrue();
    }

    @Test
    void volatileLruOnlyEvictsKeysWithTtl() {
        Keyspace ks = Keyspaces.sharded(1, LockType.RWLOCK);
        ks.set(Bytes.ascii("perm"), Bytes.ascii("keep"));
        ks.set(Bytes.ascii("temp"), Bytes.ascii("drop"), SetCondition.ALWAYS, 60_000L);

        long oneEntryBudget = ks.usedMemoryBytes() - 1;
        int evicted = ks.evictIfNeeded(oneEntryBudget, EvictionPolicy.VOLATILE_LRU, 5);
        assertThat(evicted).isEqualTo(1);
        assertThat(ks.exists(Bytes.ascii("perm"))).isTrue();
        assertThat(ks.exists(Bytes.ascii("temp"))).isFalse();
    }
}
