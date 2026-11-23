package io.github.ogulcanogul1.miniredis.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedBlockingQueueTest {

    @Test
    void fifoOrderAndCapacityLimitsWork() throws InterruptedException {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(2);
        assertThat(q.capacity()).isEqualTo(2);
        assertThat(q.offer("a")).isTrue();
        assertThat(q.offer("b")).isTrue();
        assertThat(q.offer("c")).isFalse();
        assertThat(q.size()).isEqualTo(2);

        assertThat(q.take()).isEqualTo("a");
        assertThat(q.poll(50, TimeUnit.MILLISECONDS)).isEqualTo("b");
        assertThat(q.poll(20, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void putBlocksWhenFullAndCloseUnblocksWithExceptionOrDrains() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);

        AtomicBoolean failedWithClosed = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            started.countDown();
            try {
                q.put(2);
            } catch (IllegalStateException expected) {
                failedWithClosed.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        producer.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(30);

        q.close();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(failedWithClosed.get()).isTrue();
        // Existing item can still be drained after close(), then take() returns null.
        assertThat(q.take()).isEqualTo(1);
        assertThat(q.take()).isNull();
        assertThatThrownBy(() -> q.put(3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void multipleProducersAndConsumersLoseOrDuplicateNothing() throws Exception {
        int producers = 4;
        int consumers = 4;
        int perProducer = 500;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(16);
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        CountDownLatch consumersDone = new CountDownLatch(consumers);

        for (int p = 0; p < producers; p++) {
            int base = p * perProducer;
            new Thread(() -> {
                        try {
                            gate.await();
                            for (int i = 0; i < perProducer; i++) {
                                q.put(base + i);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            producersDone.countDown();
                        }
                    })
                    .start();
        }
        for (int c = 0; c < consumers; c++) {
            new Thread(() -> {
                        try {
                            gate.await();
                            Integer item;
                            while ((item = q.take()) != null) {
                                consumed.add(item);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            consumersDone.countDown();
                        }
                    })
                    .start();
        }

        gate.countDown();
        assertThat(producersDone.await(5, TimeUnit.SECONDS)).isTrue();
        q.close();
        assertThat(consumersDone.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(consumed).hasSize(producers * perProducer);
        assertThat(consumed.stream().distinct().count()).isEqualTo(producers * perProducer);
    }
}
