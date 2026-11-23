package io.github.ogulcanogul1.miniredis.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.Main;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
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

    @Test
    void forcedKillCrashRecoveryWithAlwaysFsyncLosesZeroKeys(@TempDir Path dir) throws Exception {
        Path aof = dir.resolve("crash.aof");
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process proc = new ProcessBuilder(
                        javaBin,
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        "--port",
                        Integer.toString(port),
                        "--loglevel",
                        "warning",
                        "--appendonly",
                        "yes",
                        "--appendfilename",
                        aof.toString(),
                        "--appendfsync",
                        "always")
                .redirectErrorStream(true)
                .start();
        try {
            try (Socket socket = connectWithRetry("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                for (int i = 0; i < 50; i++) {
                    assertThat(send(out, in, "SET", "k:" + i, "v:" + i)).isEqualTo("+OK\r\n");
                }
            }
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
        } finally {
            proc.destroyForcibly();
        }

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
        try (RedisServer recovered = new RedisServer(cfg)) {
            recovered.start();
            try (Socket socket = new Socket("127.0.0.1", recovered.port())) {
                OutputStream out = socket.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                for (int i = 0; i < 50; i++) {
                    String expected = "v:" + i;
                    assertThat(send(out, in, "GET", "k:" + i))
                            .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
                }
            }
        }
    }

    private static Socket connectWithRetry(String host, int port) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                return new Socket(host, port);
            } catch (IOException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                Thread.sleep(50);
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
