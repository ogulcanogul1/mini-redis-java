package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** End-to-end TCP integration test for the Phase 3 single-client server. */
class SingleThreadServerIT {

    @Test
    void runsFullPhase1AndPhase3CommandSuiteOverTcp() throws Exception {
        try (RedisServer server = new RedisServer(ServerConfig.onPort(0))) {
            server.start();

            try (TestRespClient client = new TestRespClient("127.0.0.1", server.port())) {
                client.sendCommand("PING");
                assertThat(ascii(client.readFrame())).isEqualTo("+PONG\r\n");

                client.sendCommand("PING", "hello");
                assertThat(ascii(client.readFrame())).isEqualTo("$5\r\nhello\r\n");

                client.sendCommand("ECHO", "mini-redis");
                assertThat(ascii(client.readFrame())).isEqualTo("$10\r\nmini-redis\r\n");

                client.sendCommand("SET", "user:1", "alice");
                assertThat(ascii(client.readFrame())).isEqualTo("+OK\r\n");

                client.sendCommand("GET", "user:1");
                assertThat(ascii(client.readFrame())).isEqualTo("$5\r\nalice\r\n");

                client.sendCommand("EXISTS", "user:1", "user:1", "missing");
                assertThat(ascii(client.readFrame())).isEqualTo(":2\r\n");

                client.sendCommand("KEYS", "user:*");
                assertThat(ascii(client.readFrame())).isEqualTo("*1\r\n$6\r\nuser:1\r\n");

                client.sendCommand("DBSIZE");
                assertThat(ascii(client.readFrame())).isEqualTo(":1\r\n");

                client.sendCommand("DEL", "user:1");
                assertThat(ascii(client.readFrame())).isEqualTo(":1\r\n");

                client.sendCommand("GET", "user:1");
                assertThat(ascii(client.readFrame())).isEqualTo("$-1\r\n");

                client.sendCommand("FLUSHDB");
                assertThat(ascii(client.readFrame())).isEqualTo("+OK\r\n");

                client.sendCommand("QUIT");
                assertThat(ascii(client.readFrame())).isEqualTo("+OK\r\n");
                assertThat(client.readNextByte()).isEqualTo(-1);
            }
        }
    }

    @Test
    void handlesCommandErrorsAndKeepsConnectionAlive() throws Exception {
        try (RedisServer server = new RedisServer(ServerConfig.onPort(0))) {
            server.start();

            try (TestRespClient client = new TestRespClient("127.0.0.1", server.port())) {
                client.sendCommand("GET");
                assertThat(ascii(client.readFrame())).isEqualTo("-ERR wrong number of arguments for 'get' command\r\n");

                client.sendCommand("noSuchCmd", "x");
                assertThat(ascii(client.readFrame()))
                        .isEqualTo("-ERR unknown command 'noSuchCmd', with args beginning with: 'x' \r\n");

                client.sendCommand("PING");
                assertThat(ascii(client.readFrame())).isEqualTo("+PONG\r\n");
            }
        }
    }

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
