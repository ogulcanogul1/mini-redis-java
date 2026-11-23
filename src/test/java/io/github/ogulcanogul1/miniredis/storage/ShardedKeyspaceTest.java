package io.github.ogulcanogul1.miniredis.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShardedKeyspaceTest extends KeyspaceContractTest {

    @Override
    protected Keyspace newKeyspace() {
        return Keyspaces.sharded(16, LockType.RWLOCK);
    }

    @Test
    void rejectsNonPowerOfTwoShardCount() {
        assertThatThrownBy(() -> Keyspaces.sharded(0, LockType.MUTEX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keyspaces.sharded(3, LockType.RWLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incrByMsetMgetRenameAndDeleteAllContract() {
        Keyspace ks = Keyspaces.sharded(4, LockType.MUTEX);
        Bytes k1 = Bytes.ascii("k1");
        Bytes k2 = Bytes.ascii("k2");
        Bytes k3 = Bytes.ascii("k3");

        assertThat(ks.incrBy(k1, 1)).isEqualTo(new IncrResult.Ok(1));
        assertThat(ks.incrBy(k1, 5)).isEqualTo(new IncrResult.Ok(6));

        ks.set(k2, Bytes.ascii("abc"));
        assertThat(ks.incrBy(k2, 1)).isEqualTo(new IncrResult.NotAnInteger());

        ks.set(k3, Bytes.ascii(Long.toString(Long.MAX_VALUE)));
        assertThat(ks.incrBy(k3, 1)).isEqualTo(new IncrResult.Overflow());

        ks.mset(List.of(k1, Bytes.ascii("10"), k2, Bytes.ascii("20")));
        assertThat(ks.mget(List.of(k1, k2, Bytes.ascii("missing"))))
                .containsExactly(
                        java.util.Optional.of(Bytes.ascii("10")),
                        java.util.Optional.of(Bytes.ascii("20")),
                        java.util.Optional.empty());

        assertThat(ks.rename(k1, k2)).isTrue();
        assertThat(ks.exists(k1)).isFalse();
        assertThat(ks.get(k2)).contains(Bytes.ascii("10"));
        assertThat(ks.rename(Bytes.ascii("missing"), k1)).isFalse();
        assertThat(ks.deleteAll(List.of(k2, k2, k3))).isEqualTo(2);
    }

    @Test
    void concurrentIncrementsAndCrossShardRenamesAreDeadlockFreeAndExact() throws Exception {
        Keyspace ks = Keyspaces.inMemory();
        int threads = 8;
        int incrementsPerThread = 2000;
        Bytes counter = Bytes.ascii("shared-counter");
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads + 2);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                        try {
                            gate.await();
                            for (int i = 0; i < incrementsPerThread; i++) {
                                if (!(ks.incrBy(counter, 1) instanceof IncrResult.Ok)) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (Throwable e) {
                            failures.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    })
                    .start();
        }

        // Cross-shard multi-key operations in opposite key orders to exercise sorted lock ordering.
        Bytes a = Bytes.ascii("key-alpha");
        Bytes b = Bytes.ascii("key-omega");
        Bytes rA = Bytes.ascii("rename-alpha");
        Bytes rB = Bytes.ascii("rename-omega");
        ks.mset(List.of(a, Bytes.ascii("init"), b, Bytes.ascii("init")));
        ks.set(rA, Bytes.ascii("1"));

        for (int dir = 0; dir < 2; dir++) {
            Bytes tag = Bytes.ascii(Integer.toString(dir));
            List<Bytes> pair = dir == 0 ? List.of(a, tag, b, tag) : List.of(b, tag, a, tag);
            List<Bytes> readOrder = dir == 0 ? List.of(a, b) : List.of(b, a);
            Bytes src = dir == 0 ? rA : rB;
            Bytes dst = dir == 0 ? rB : rA;
            new Thread(() -> {
                        try {
                            gate.await();
                            for (int i = 0; i < 500; i++) {
                                ks.mset(pair);
                                List<java.util.Optional<Bytes>> vals = new ArrayList<>(ks.mget(readOrder));
                                if (vals.size() != 2 || !vals.get(0).equals(vals.get(1))) {
                                    failures.incrementAndGet();
                                }
                                ks.rename(src, dst);
                            }
                        } catch (Throwable e) {
                            failures.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    })
                    .start();
        }

        gate.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isZero();
        assertThat(ks.get(counter)).contains(Bytes.ascii(Integer.toString(threads * incrementsPerThread)));
    }
}
