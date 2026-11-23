package io.github.ogulcanogul1.miniredis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReplicationIT {

    @Test
    void replicaSyncsInitialSnapshotAndStreamsWritesFromMaster() throws Exception {
        try (RedisServer master = new RedisServer(ServerConfig.onPort(0))) {
            master.start();
            try (Socket mSock = new Socket("127.0.0.1", master.port())) {
                mSock.setSoTimeout(3000);
                assertEquals("+OK\r\n", send(mSock, "*3\r\n$3\r\nSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n"));
                assertEquals(":2\r\n", send(mSock, "*4\r\n$5\r\nRPUSH\r\n$2\r\nl1\r\n$1\r\na\r\n$1\r\nb\r\n"));

                ServerConfig repCfg = ConfigParser.parseArgs(
                        new String[] {"--port", "0", "--replicaof", "127.0.0.1", Integer.toString(master.port())});
                try (RedisServer replica = new RedisServer(repCfg)) {
                    replica.start();
                    try (Socket rSock = new Socket("127.0.0.1", replica.port())) {
                        rSock.setSoTimeout(3000);

                        awaitEquals("$2\r\nv1\r\n", rSock, "*2\r\n$3\r\nGET\r\n$2\r\nk1\r\n");
                        assertEquals(":2\r\n", send(rSock, "*2\r\n$4\r\nLLEN\r\n$2\r\nl1\r\n"));

                        String roReply = send(rSock, "*3\r\n$3\r\nSET\r\n$2\r\nx1\r\n$1\r\n1\r\n");
                        assertTrue(roReply.startsWith("-READONLY "));

                        assertEquals("+OK\r\n", send(mSock, "*3\r\n$3\r\nSET\r\n$2\r\nk2\r\n$2\r\nv2\r\n"));
                        assertEquals(":1\r\n", send(mSock, "*2\r\n$4\r\nINCR\r\n$3\r\ncnt\r\n"));

                        awaitEquals("$2\r\nv2\r\n", rSock, "*2\r\n$3\r\nGET\r\n$2\r\nk2\r\n");
                        awaitEquals("$1\r\n1\r\n", rSock, "*2\r\n$3\r\nGET\r\n$3\r\ncnt\r\n");

                        String roleMaster = send(mSock, "*1\r\n$4\r\nROLE\r\n");
                        assertTrue(roleMaster.contains("master"));
                        String roleReplica = send(rSock, "*1\r\n$4\r\nROLE\r\n");
                        assertTrue(roleReplica.contains("slave"));
                    }
                }
            }
        }
    }

    private static void awaitEquals(String expected, Socket s, String resp) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        String last = "";
        while (System.nanoTime() < deadline) {
            last = send(s, resp);
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, last);
    }

    private static String send(Socket s, String resp) throws Exception {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        byte[] buf = new byte[4096];
        int n = in.read(buf);
        return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
    }
}
