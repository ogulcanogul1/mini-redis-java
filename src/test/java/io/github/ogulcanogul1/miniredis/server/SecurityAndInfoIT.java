package io.github.ogulcanogul1.miniredis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.Reply;
import io.github.ogulcanogul1.miniredis.commands.SessionAuthState;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.EvictionPolicy;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityAndInfoIT {

    @Test
    void requirePassEnforcesAuthAndInfoExcludesSecret() throws Exception {
        ServerConfig cfg = ConfigParser.parseArgs(new String[] {"--port", "0", "--requirepass", "s3cr3t"});
        try (RedisServer server = new RedisServer(cfg)) {
            server.start();
            try (Socket s = new Socket("127.0.0.1", server.port())) {
                s.setSoTimeout(3000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                assertEquals("-NOAUTH Authentication required.\r\n", send(out, in, "*1\r\n$4\r\nPING\r\n"));
                assertEquals(
                        "-WRONGPASS invalid username-password pair or user is disabled.\r\n",
                        send(out, in, "*2\r\n$4\r\nAUTH\r\n$5\r\nwrong\r\n"));
                assertEquals("+OK\r\n", send(out, in, "*2\r\n$4\r\nAUTH\r\n$6\r\ns3cr3t\r\n"));
                assertEquals("+PONG\r\n", send(out, in, "*1\r\n$4\r\nPING\r\n"));

                String info = send(out, in, "*1\r\n$4\r\nINFO\r\n");
                assertTrue(info.contains("# Server"));
                assertTrue(info.contains("# Clients"));
                assertTrue(info.contains("# Memory"));
                assertTrue(info.contains("# Stats"));
                assertTrue(info.contains("# Persistence"));
                assertTrue(info.contains("total_error_replies:2"));
                assertFalse(info.contains("s3cr3t"));
            }
        }
    }

    @Test
    void protectedModeBlocksNonLoopbackWhenNoPasswordIsSet() {
        Dispatcher dispatcher = new Dispatcher(
                CommandTable.standard(),
                new CommandContext(Keyspaces.inMemory()),
                (spec, argv, reply) -> {},
                0L,
                EvictionPolicy.NOEVICTION,
                null,
                true);

        SessionAuthState remoteNoAuth = dispatcher.newSessionState(false);
        Reply reply = dispatcher.dispatch(List.of(Bytes.utf8("PING")), remoteNoAuth);
        assertTrue(reply instanceof Reply.ErrorReply err && err.message().startsWith("DENIED "));

        SessionAuthState localNoAuth = dispatcher.newSessionState(true);
        assertEquals(Reply.PONG, dispatcher.dispatch(List.of(Bytes.utf8("PING")), localNoAuth));
    }

    private static String send(OutputStream out, InputStream in, String resp) throws Exception {
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        byte[] buf = new byte[8192];
        int n = in.read(buf);
        return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
    }
}
