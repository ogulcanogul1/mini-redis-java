package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C10K-style simultaneous connection stress test run via {@code mvn -B verify -Pstress}. */
class C10kStressTest {

    @Test
    void multiReactorMaintainsThousandSimultaneousConnectionsOnFourIoThreads() throws Exception {
        int connections = 1000;
        ServerConfig cfg = new ServerConfig(
                ServerConfig.DEFAULT_BIND,
                0,
                ServerConfig.IoModel.MULTI_REACTOR,
                ServerConfig.LogLevel.WARNING,
                RespLimits.defaults(),
                ServerConfig.DEFAULT_WORKER_THREADS,
                ServerConfig.DEFAULT_QUEUE_CAPACITY,
                16,
                LockType.RWLOCK,
                4,
                2000,
                ServerConfig.DEFAULT_CLIENT_OUTPUT_BUFFER_LIMIT,
                0);

        List<TestRespClient> clients = new ArrayList<>(connections);
        try (RedisServer server = new RedisServer(cfg)) {
            server.start();
            try {
                for (int i = 0; i < connections; i++) {
                    TestRespClient c = new TestRespClient("127.0.0.1", server.port());
                    clients.add(c);
                    c.sendCommand("PING");
                    assertThat(new String(c.readFrame(), StandardCharsets.US_ASCII))
                            .isEqualTo("+PONG\r\n");
                }
                assertThat(server.stats().connectedClients()).isEqualTo(connections);

                for (TestRespClient c : clients) {
                    c.sendCommand("INCR", "c10k:hits");
                }
                for (TestRespClient c : clients) {
                    assertThat(new String(c.readFrame(), StandardCharsets.US_ASCII))
                            .startsWith(":");
                }
                try (TestRespClient check = clients.getFirst()) {
                    check.sendCommand("GET", "c10k:hits");
                    String expected = Integer.toString(connections);
                    assertThat(new String(check.readFrame(), StandardCharsets.US_ASCII))
                            .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
                }
            } finally {
                for (TestRespClient c : clients) {
                    ClientSessionLoop.closeQuietly(() -> c.close());
                }
            }
            server.stop();
            server.awaitTermination();
            assertThat(server.stats().connectedClients()).isZero();
        }
    }
}
