package io.github.ogulcanogul1.miniredis.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RespSessionTest {

    private RespSession session;

    @BeforeEach
    void setUp() {
        session = newSession(RespLimits.defaults());
    }

    @Test
    void handlesPingAndPartialRequests() {
        byte[] firstHalf = ascii("*1\r\n$4\r\nPI");
        byte[] secondHalf = ascii("NG\r\n");

        assertThat(session.onData(firstHalf, 0, firstHalf.length)).isEmpty();
        assertThat(session.onData(secondHalf, 0, secondHalf.length)).isEqualTo(ascii("+PONG\r\n"));
        assertThat(session.shouldClose()).isFalse();
    }

    @Test
    void runsSetGetDelKeysAndBinaryValuesEndToEnd() {
        assertThat(send("SET k v\r\n")).isEqualTo(ascii("+OK\r\n"));
        assertThat(send("GET k\r\n")).isEqualTo(ascii("$1\r\nv\r\n"));
        assertThat(send("KEYS *\r\n")).isEqualTo(ascii("*1\r\n$1\r\nk\r\n"));
        assertThat(send("DEL k\r\n")).isEqualTo(ascii(":1\r\n"));
        assertThat(send("GET k\r\n")).isEqualTo(ascii("$-1\r\n"));

        byte[] binaryReq = {
            '*',
            '3',
            '\r',
            '\n',
            '$',
            '3',
            '\r',
            '\n',
            'S',
            'E',
            'T',
            '\r',
            '\n',
            '$',
            '1',
            '\r',
            '\n',
            'b',
            '\r',
            '\n',
            '$',
            '4',
            '\r',
            '\n',
            'a',
            0,
            '\r',
            (byte) 0xFF,
            '\r',
            '\n'
        };
        assertThat(session.onData(binaryReq, 0, binaryReq.length)).isEqualTo(ascii("+OK\r\n"));
        byte[] expectedGet = {'$', '4', '\r', '\n', 'a', 0, '\r', (byte) 0xFF, '\r', '\n'};
        assertThat(send("GET b\r\n")).isEqualTo(expectedGet);
    }

    @Test
    void preservesOrderInMixedPipelineAndProducesSameOutputForEveryChunkSize() {
        byte[] wire = ascii("PING\r\n*3\r\n$3\r\nSET\r\n$1\r\nx\r\n$2\r\nhi\r\nGET x\r\n");
        byte[] expected = ascii("+PONG\r\n+OK\r\n$2\r\nhi\r\n");

        for (int chunk = 1; chunk <= wire.length; chunk++) {
            RespSession s = newSession(RespLimits.defaults());
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            for (int off = 0; off < wire.length; off += chunk) {
                byte[] out = s.onData(wire, off, Math.min(chunk, wire.length - off));
                collected.writeBytes(out);
            }
            assertThat(collected.toByteArray()).isEqualTo(expected);
            assertThat(s.shouldClose()).isFalse();
        }
    }

    @Test
    void keepsConnectionOpenOnCommandErrorsAndSingleLineUnknownCommand() {
        assertThat(send("GET\r\n")).isEqualTo(ascii("-ERR wrong number of arguments for 'get' command\r\n"));
        assertThat(session.shouldClose()).isFalse();
        assertThat(send("PING\r\n")).isEqualTo(ascii("+PONG\r\n"));

        assertThat(send("*2\r\n$3\r\nFOO\r\n$5\r\na\r\nbc\r\n"))
                .isEqualTo(ascii("-ERR unknown command 'FOO', with args beginning with: 'a  bc' \r\n"));
        assertThat(send("*1\r\n$0\r\n\r\n"))
                .isEqualTo(ascii("-ERR unknown command '', with args beginning with: \r\n"));
        assertThat(session.shouldClose()).isFalse();
    }

    @Test
    void protocolErrorRepliesStopsPipelineAndMarksClosed() {
        byte[] out = send("PING\r\n*x\r\n*1\r\n$4\r\nPING\r\n");
        assertThat(out).isEqualTo(ascii("+PONG\r\n-ERR Protocol error: invalid multibulk length\r\n"));
        assertThat(session.shouldClose()).isTrue();

        byte[] more = ascii("PING\r\n");
        assertThatThrownBy(() -> session.onData(more, 0, more.length)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closesWhenLimitsAreExceeded() {
        RespSession small = newSession(new RespLimits(2, 4, 8));
        byte[] out = small.onData(ascii("*1\r\n$5\r\n"), 0, 8);
        assertThat(out).isEqualTo(ascii("-ERR Protocol error: invalid bulk length\r\n"));
        assertThat(small.shouldClose()).isTrue();
    }

    @Test
    void quitStopsPipelineAndMarksClosed() {
        Keyspace ks = Keyspaces.inMemory();
        RespSession s =
                new RespSession(new Dispatcher(CommandTable.standard(), new CommandContext(ks)), RespLimits.defaults());
        byte[] wire = ascii("PING\r\nQUIT\r\nSET afterquit 1\r\n");

        assertThat(s.onData(wire, 0, wire.length)).isEqualTo(ascii("+PONG\r\n+OK\r\n"));
        assertThat(s.shouldClose()).isTrue();
        assertThat(ks.get(Bytes.ascii("afterquit"))).isEmpty();

        byte[] more = ascii("PING\r\n");
        assertThatThrownBy(() -> s.onData(more, 0, more.length)).isInstanceOf(IllegalStateException.class);
    }

    private byte[] send(String s) {
        byte[] bytes = ascii(s);
        return session.onData(bytes, 0, bytes.length);
    }

    private static RespSession newSession(RespLimits limits) {
        Dispatcher dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory()));
        return new RespSession(dispatcher, limits);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
