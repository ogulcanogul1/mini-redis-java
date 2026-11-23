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

/** TCP integration tests for {@code io-model=virtual} and {@code io-model=multi-reactor}. */
class MultiReactorServerIT {

    @Test
    void singleAndMultiReactorHandleIdlePartialWritesQuitAndParallelIncr() throws Exception {
        for (ServerConfig.IoModel model : List.of(ServerConfig.IoModel.VIRTUAL, ServerConfig.IoModel.MULTI_REACTOR)) {
            int ioThreads = model == ServerConfig.IoModel.MULTI_REACTOR ? 4 : 1;
            runModelCheck(model, ioThreads);
        }
        // Single-reactor configuration (Redis-like single event loop).
        runModelCheck(ServerConfig.IoModel.MULTI_REACTOR, 1);
    }

    private static void runModelCheck(ServerConfig.IoModel model, int ioThreads) throws Exception {
        ServerConfig cfg = new ServerConfig(
                ServerConfig.DEFAULT_BIND,
                0,
                model,
                ServerConfig.LogLevel.WARNING,
                RespLimits.defaults(),
                ServerConfig.DEFAULT_WORKER_THREADS,
                ServerConfig.DEFAULT_QUEUE_CAPACITY,
                16,
                LockType.RWLOCK,
                ioThreads,
                100,
                ServerConfig.DEFAULT_CLIENT_OUTPUT_BUFFER_LIMIT,
                0);
        int clients = 16;
        int perClient = 100;
        try (RedisServer server = new RedisServer(cfg);
                ExecutorService callers = Executors.newFixedThreadPool(clients)) {
            server.start();

            // Verify idle client + split write + QUIT while another client works freely.
            try (TestRespClient idle = new TestRespClient("127.0.0.1", server.port());
                    TestRespClient active = new TestRespClient("127.0.0.1", server.port())) {
                active.sendAscii("*2\r\n$4\r\nEC");
                Thread.sleep(20);
                active.sendAscii("HO\r\n$2\r\nhi\r\n");
                assertThat(new String(active.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("$2\r\nhi\r\n");

                idle.sendCommand("QUIT");
                assertThat(new String(idle.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+OK\r\n");
                assertThat(idle.readNextByte()).isEqualTo(-1);
            }

            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(clients);
            AtomicInteger errors = new AtomicInteger();

            for (int c = 0; c < clients; c++) {
                callers.execute(() -> {
                    try (TestRespClient client = new TestRespClient("127.0.0.1", server.port())) {
                        gate.await();
                        for (int i = 0; i < perClient; i++) {
                            client.sendCommand("INCR", "reactor:cnt");
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
                check.sendCommand("GET", "reactor:cnt");
                String expected = Integer.toString(clients * perClient);
                assertThat(new String(check.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
            }

            server.stop();
            server.awaitTermination();
            assertThat(server.stats().connectedClients()).isZero();
        }
    }
}
