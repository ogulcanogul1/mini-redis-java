package io.github.ogulcanogul1.miniredis.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PubSubIT {

    @Test
    void subscriberReceivesPublishedMessagesOverMultiReactor() throws Exception {
        try (RedisServer server = new RedisServer(ServerConfig.onPort(0))) {
            server.start();
            try (Socket sub = new Socket("127.0.0.1", server.port());
                    Socket pub = new Socket("127.0.0.1", server.port())) {
                sub.setSoTimeout(3000);
                pub.setSoTimeout(3000);

                String subResp = send(sub, "*2\r\n$9\r\nSUBSCRIBE\r\n$4\r\nnews\r\n");
                assertEquals("*3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n", subResp);

                String pubResp = send(pub, "*3\r\n$7\r\nPUBLISH\r\n$4\r\nnews\r\n$5\r\nhello\r\n");
                assertEquals(":1\r\n", pubResp);

                String pushed = readAvailable(sub);
                assertTrue(pushed.contains("$7\r\nmessage\r\n$4\r\nnews\r\n$5\r\nhello\r\n"));

                String unsubResp = send(sub, "*2\r\n$11\r\nUNSUBSCRIBE\r\n$4\r\nnews\r\n");
                assertEquals("*3\r\n$11\r\nunsubscribe\r\n$4\r\nnews\r\n:0\r\n", unsubResp);

                assertEquals(":0\r\n", send(pub, "*3\r\n$7\r\nPUBLISH\r\n$4\r\nnews\r\n$5\r\nafter\r\n"));
            }
        }
    }

    private static String send(Socket s, String resp) throws Exception {
        OutputStream out = s.getOutputStream();
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readAvailable(s);
    }

    private static String readAvailable(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        byte[] buf = new byte[4096];
        int n = in.read(buf);
        return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
    }
}
