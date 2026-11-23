package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Concurrency stress tests run via {@code mvn -B verify -Pstress}. */
class ConcurrencyStressTest {

    @Test
    void highContentionIncrAndMultiKeyOpsNeverLoseUpdatesOrDeadlock() throws Exception {
        int clients = 24;
        int opsPerClient = 500;
        ServerConfig cfg = new ServerConfig(
                ServerConfig.DEFAULT_BIND,
                0,
                ServerConfig.IoModel.THREAD_POOL,
                ServerConfig.LogLevel.WARNING,
                RespLimits.defaults(),
                12,
                128,
                16,
                LockType.RWLOCK);

        try (RedisServer server = new RedisServer(cfg);
                ExecutorService callers = Executors.newFixedThreadPool(clients)) {
            server.start();
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(clients);
            AtomicInteger failures = new AtomicInteger();

            for (int c = 0; c < clients; c++) {
                int cid = c;
                callers.execute(() -> {
                    try (TestRespClient client = new TestRespClient("127.0.0.1", server.port())) {
                        gate.await();
                        for (int i = 0; i < opsPerClient; i++) {
                            client.sendCommand("INCR", "stress:count");
                            byte[] frame = client.readFrame();
                            if (frame.length == 0 || frame[0] != ':') {
                                failures.incrementAndGet();
                            }
                            if (i % 10 == 0) {
                                String k1 = "pair:" + (cid % 4) + ":a";
                                String k2 = "pair:" + ((cid + 1) % 4) + ":b";
                                String val = Integer.toString(i);
                                client.sendCommand("MSET", k1, val, k2, val);
                                client.readFrame();
                                client.sendCommand("MGET", k1, k2);
                                client.readFrame();
                            }
                        }
                    } catch (Throwable t) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            gate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failures.get()).isZero();

            try (TestRespClient check = new TestRespClient("127.0.0.1", server.port())) {
                check.sendCommand("GET", "stress:count");
                String expected = Integer.toString(clients * opsPerClient);
                assertThat(new String(check.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
            }
        }
    }
}
