package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TCP integration tests verifying concurrent client handling in {@code thread-pool} and {@code thread-per-conn}. */
class MultiClientServerIT {

    @Test
    void idleFirstClientDoesNotBlockSecondClientUnderThreadPool() throws Exception {
        ServerConfig cfg = new ServerConfig(
                ServerConfig.DEFAULT_BIND,
                0,
                ServerConfig.IoModel.THREAD_POOL,
                ServerConfig.LogLevel.WARNING,
                RespLimits.defaults(),
                4,
                32,
                16,
                LockType.RWLOCK);
        try (RedisServer server = new RedisServer(cfg)) {
            server.start();

            // Client 1 connects and stays idle without closing its socket.
            try (TestRespClient idle = new TestRespClient("127.0.0.1", server.port());
                    TestRespClient active = new TestRespClient("127.0.0.1", server.port())) {
                active.sendCommand("PING");
                assertThat(new String(active.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");

                active.sendCommand("INCR", "hits");
                assertThat(new String(active.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo(":1\r\n");

                idle.sendCommand("PING");
                assertThat(new String(idle.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");
            }
        }
    }

    @Test
    void parallelClientsIncrementSharedCounterExactlyInBothConcurrentModels() throws Exception {
        for (ServerConfig.IoModel model :
                List.of(ServerConfig.IoModel.THREAD_PER_CONN, ServerConfig.IoModel.THREAD_POOL)) {
            ServerConfig cfg = new ServerConfig(
                    ServerConfig.DEFAULT_BIND,
                    0,
                    model,
                    ServerConfig.LogLevel.WARNING,
                    RespLimits.defaults(),
                    8,
                    64,
                    16,
                    LockType.RWLOCK);
            int clients = 16;
            int perClient = 100;
            try (RedisServer server = new RedisServer(cfg);
                    ExecutorService callers = Executors.newFixedThreadPool(clients)) {
                server.start();
                CountDownLatch gate = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(clients);
                AtomicInteger errors = new AtomicInteger();

                for (int c = 0; c < clients; c++) {
                    callers.execute(() -> {
                        try (TestRespClient client = new TestRespClient("127.0.0.1", server.port())) {
                            gate.await();
                            for (int i = 0; i < perClient; i++) {
                                client.sendCommand("INCR", "total");
                                client.readFrame();
                            }
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                gate.countDown();
                assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(errors.get()).isZero();

                try (TestRespClient check = new TestRespClient("127.0.0.1", server.port())) {
                    check.sendCommand("GET", "total");
                    String expected = Integer.toString(clients * perClient);
                    assertThat(new String(check.readFrame(), StandardCharsets.US_ASCII))
                            .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
                }
            }
        }
    }
}
