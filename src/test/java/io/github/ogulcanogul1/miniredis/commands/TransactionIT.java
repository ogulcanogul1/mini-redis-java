package io.github.ogulcanogul1.miniredis.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TransactionIT {

    @Test
    void watchAbortsExecWithNullArrayWhenAnotherClientModifiesKey() throws Exception {
        try (RedisServer server = new RedisServer(ServerConfig.onPort(0))) {
            server.start();
            try (Socket s1 = new Socket("127.0.0.1", server.port());
                    Socket s2 = new Socket("127.0.0.1", server.port())) {
                s1.setSoTimeout(3000);
                s2.setSoTimeout(3000);

                assertEquals("+OK\r\n", send(s1, "*3\r\n$3\r\nSET\r\n$1\r\nx\r\n$1\r\n1\r\n"));
                assertEquals("+OK\r\n", send(s1, "*2\r\n$5\r\nWATCH\r\n$1\r\nx\r\n"));
                assertEquals("+OK\r\n", send(s2, "*3\r\n$3\r\nSET\r\n$1\r\nx\r\n$1\r\n2\r\n"));

                assertEquals("+OK\r\n", send(s1, "*1\r\n$5\r\nMULTI\r\n"));
                assertEquals("+QUEUED\r\n", send(s1, "*3\r\n$3\r\nSET\r\n$1\r\nx\r\n$1\r\n3\r\n"));
                assertEquals("*-1\r\n", send(s1, "*1\r\n$4\r\nEXEC\r\n"));
                assertEquals("$1\r\n2\r\n", send(s2, "*2\r\n$3\r\nGET\r\n$1\r\nx\r\n"));
            }
        }
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
