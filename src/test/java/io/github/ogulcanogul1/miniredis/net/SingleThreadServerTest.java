package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SingleThreadServerTest {

    @Test
    void servesSplitWritesPipeliningAndSubsequentClients() throws Exception {
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory()));
        try (SingleThreadServer server = new SingleThreadServer("127.0.0.1", 0, dispatcher, RespLimits.defaults())) {
            assertThat(server.port()).isEqualTo(-1);
            server.start();
            assertThat(server.port()).isPositive();
            assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);

            try (Socket s1 = new Socket("127.0.0.1", server.port())) {
                s1.setSoTimeout(3000);
                OutputStream out = s1.getOutputStream();
                InputStream in = s1.getInputStream();

                out.write("*2\r\n$4\r\nEC".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(20);
                out.write("HO\r\n$2\r\nhi\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                assertThat(readAscii(in, "$2\r\nhi\r\n".length())).isEqualTo("$2\r\nhi\r\n");

                out.write("SET k v\r\nGET k\r\nQUIT\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String expected = "+OK\r\n$1\r\nv\r\n+OK\r\n";
                assertThat(readAscii(in, expected.length())).isEqualTo(expected);
                assertThat(in.read()).isEqualTo(-1);
            }

            try (Socket s2 = new Socket("127.0.0.1", server.port())) {
                s2.setSoTimeout(3000);
                OutputStream out = s2.getOutputStream();
                InputStream in = s2.getInputStream();
                out.write("*bad\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String err = "-ERR Protocol error: invalid multibulk length\r\n";
                assertThat(readAscii(in, err.length())).isEqualTo(err);
                assertThat(in.read()).isEqualTo(-1);
            }

            try (Socket s3 = new Socket("127.0.0.1", server.port())) {
                s3.getOutputStream().write("*2\r\n$3\r\nGET".getBytes(StandardCharsets.US_ASCII));
                s3.getOutputStream().flush();
            }

            try (Socket s4 = new Socket("127.0.0.1", server.port())) {
                s4.setSoTimeout(3000);
                s4.getOutputStream().write("GET k\r\n".getBytes(StandardCharsets.US_ASCII));
                s4.getOutputStream().flush();
                assertThat(readAscii(s4.getInputStream(), "$1\r\nv\r\n".length()))
                        .isEqualTo("$1\r\nv\r\n");
            }
        }
    }

    @Test
    void startFailsWhenPortIsAlreadyInUse() throws Exception {
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory()));
        try (SingleThreadServer first = new SingleThreadServer("127.0.0.1", 0, dispatcher, RespLimits.defaults())) {
            first.start();
            try (SingleThreadServer second =
                    new SingleThreadServer("127.0.0.1", first.port(), dispatcher, RespLimits.defaults())) {
                assertThatThrownBy(second::start).isInstanceOf(IOException.class);
            }
        }
    }

    private static String readAscii(InputStream in, int len) throws IOException {
        return new String(in.readNBytes(len), StandardCharsets.US_ASCII);
    }
}
