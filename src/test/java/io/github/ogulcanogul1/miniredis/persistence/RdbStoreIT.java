package io.github.ogulcanogul1.miniredis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ogulcanogul1.miniredis.server.ConfigParser;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RdbStoreIT {

    @TempDir
    Path tempDir;

    @Test
    void savePersistsAllTypesAndLoadsOnRestart() throws Exception {
        Path dbFile = tempDir.resolve("dump.rdb");
        ServerConfig cfg = ConfigParser.parseArgs(new String[] {"--port", "0", "--dbfilename", dbFile.toString()});

        try (RedisServer server = new RedisServer(cfg)) {
            server.start();
            try (Socket s = new Socket("127.0.0.1", server.port())) {
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                assertEquals("+OK\r\n", send(out, in, "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$3\r\nval\r\n"));
                assertEquals(":2\r\n", send(out, in, "*4\r\n$5\r\nRPUSH\r\n$4\r\nlist\r\n$1\r\na\r\n$1\r\nb\r\n"));
                assertEquals(":1\r\n", send(out, in, "*4\r\n$4\r\nHSET\r\n$4\r\nhash\r\n$1\r\nf\r\n$1\r\nv\r\n"));
                assertEquals(":1\r\n", send(out, in, "*3\r\n$4\r\nSADD\r\n$3\r\nset\r\n$1\r\nm\r\n"));
                assertEquals(":1\r\n", send(out, in, "*4\r\n$4\r\nZADD\r\n$4\r\nzset\r\n$3\r\n1.5\r\n$5\r\nalice\r\n"));
                assertEquals("+OK\r\n", send(out, in, "*1\r\n$4\r\nSAVE\r\n"));
            }
        }

        assertTrue(Files.exists(dbFile));

        try (RedisServer restarted = new RedisServer(cfg)) {
            restarted.start();
            try (Socket s = new Socket("127.0.0.1", restarted.port())) {
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                assertEquals("$3\r\nval\r\n", send(out, in, "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n"));
                assertEquals(":2\r\n", send(out, in, "*2\r\n$4\r\nLLEN\r\n$4\r\nlist\r\n"));
                assertEquals("$1\r\nv\r\n", send(out, in, "*3\r\n$4\r\nHGET\r\n$4\r\nhash\r\n$1\r\nf\r\n"));
                assertEquals(":1\r\n", send(out, in, "*3\r\n$9\r\nSISMEMBER\r\n$3\r\nset\r\n$1\r\nm\r\n"));
                assertEquals("$3\r\n1.5\r\n", send(out, in, "*3\r\n$6\r\nZSCORE\r\n$4\r\nzset\r\n$5\r\nalice\r\n"));
            }
        }
    }

    @Test
    void bgsaveWritesSnapshotAsynchronously() throws Exception {
        Path dbFile = tempDir.resolve("bg.rdb");
        ServerConfig cfg = ConfigParser.parseArgs(new String[] {"--port", "0", "--dbfilename", dbFile.toString()});

        try (RedisServer server = new RedisServer(cfg)) {
            server.start();
            try (Socket s = new Socket("127.0.0.1", server.port())) {
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                assertEquals("+OK\r\n", send(out, in, "*3\r\n$3\r\nSET\r\n$2\r\nbg\r\n$2\r\nok\r\n"));
                assertEquals("+Background saving started\r\n", send(out, in, "*1\r\n$6\r\nBGSAVE\r\n"));
            }

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(dbFile) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(dbFile), "background snapshot should have been written");
        }

        try (RedisServer restarted = new RedisServer(cfg)) {
            restarted.start();
            try (Socket s = new Socket("127.0.0.1", restarted.port())) {
                s.setSoTimeout(3000);
                assertEquals(
                        "$2\r\nok\r\n",
                        send(s.getOutputStream(), s.getInputStream(), "*2\r\n$3\r\nGET\r\n$2\r\nbg\r\n"));
            }
        }
    }

    private static String send(OutputStream out, InputStream in, String resp) throws Exception {
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        byte[] buf = new byte[4096];
        int n = in.read(buf);
        return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
    }
}
