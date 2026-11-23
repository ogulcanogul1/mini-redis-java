package io.github.ogulcanogul1.miniredis.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AofPersistenceIT {

    @Test
    void dataAndTtlSurviveServerRestartWithAof(@TempDir Path dir) throws Exception {
        Path aof = dir.resolve("appendonly.aof");
        ServerConfig cfg = new ServerConfig(
                "127.0.0.1",
                0,
                ServerConfig.IoModel.MULTI_REACTOR,
                ServerConfig.LogLevel.WARNING,
                RespLimits.defaults(),
                ServerConfig.DEFAULT_WORKER_THREADS,
                ServerConfig.DEFAULT_QUEUE_CAPACITY,
                ServerConfig.DEFAULT_SHARDS,
                LockType.RWLOCK,
                2,
                ServerConfig.DEFAULT_MAX_CLIENTS,
                ServerConfig.DEFAULT_CLIENT_OUTPUT_BUFFER_LIMIT,
                0L,
                true,
                aof.toString(),
                AofFsyncPolicy.ALWAYS);

        try (RedisServer server = new RedisServer(cfg)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                OutputStream out = socket.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                assertThat(send(out, in, "SET", "persist", "hello")).isEqualTo("+OK\r\n");
                assertThat(send(out, in, "SET", "ttlkey", "v", "EX", "60")).isEqualTo("+OK\r\n");
                assertThat(send(out, in, "INCR", "counter")).isEqualTo(":1\r\n");
                assertThat(send(out, in, "INCR", "counter")).isEqualTo(":2\r\n");
            }
        }

        try (RedisServer restarted = new RedisServer(cfg)) {
            restarted.start();
            try (Socket socket = new Socket("127.0.0.1", restarted.port())) {
                OutputStream out = socket.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                assertThat(send(out, in, "GET", "persist")).isEqualTo("$5\r\nhello\r\n");
                assertThat(send(out, in, "GET", "counter")).isEqualTo("$1\r\n2\r\n");
                String ttl = send(out, in, "TTL", "ttlkey");
                long remaining = Long.parseLong(ttl.substring(1, ttl.length() - 2));
                assertThat(remaining).isBetween(1L, 60L);
            }
        }
    }

    private static String send(OutputStream out, BufferedInputStream in, String... args) throws IOException {
        out.write(AofCodec.encodeCommand(Arrays.stream(args).map(Bytes::ascii).toList()));
        out.flush();
        String line = readLine(in);
        if (line.startsWith("$") && !line.equals("$-1\r\n")) {
            int len = Integer.parseInt(line.substring(1, line.length() - 2));
            byte[] buf = in.readNBytes(len + 2);
            return line + new String(buf, StandardCharsets.US_ASCII);
        }
        return line;
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (sb.length() >= 2 && sb.charAt(sb.length() - 2) == '\r' && sb.charAt(sb.length() - 1) == '\n') {
                break;
            }
        }
        return sb.toString();
    }
}
