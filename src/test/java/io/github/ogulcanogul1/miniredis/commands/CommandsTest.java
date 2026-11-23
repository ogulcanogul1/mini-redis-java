package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ogulcanogul1.miniredis.commands.CommandSpec.Flag;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CommandsTest {

    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory()));
    }

    @Test
    void pingWithoutArgumentReturnsPong() {
        assertThat(run("PING")).isEqualTo(Reply.PONG);
    }

    @Test
    void pingWithMessageReturnsItAsBulkString() {
        assertThat(run("PING", "hello")).isEqualTo(Reply.bulk(Bytes.utf8("hello")));
    }

    @Test
    void pingWithTwoArgumentsIsWrongArity() {
        assertThat(run("PING", "a", "b")).isEqualTo(Reply.error("ERR wrong number of arguments for 'ping' command"));
    }

    @Test
    void setThenGetReturnsTheValue() {
        assertThat(run("SET", "a", "hello")).isEqualTo(Reply.OK);
        assertThat(run("GET", "a")).isEqualTo(Reply.bulk(Bytes.utf8("hello")));
    }

    @Test
    void getOfMissingKeyReturnsNil() {
        assertThat(run("GET", "nope")).isEqualTo(Reply.NIL);
    }

    @Test
    void getWithoutKeyIsWrongArity() {
        assertThat(run("GET")).isEqualTo(Reply.error("ERR wrong number of arguments for 'get' command"));
    }

    @Test
    void setWithoutValueIsWrongArity() {
        assertThat(run("SET", "a")).isEqualTo(Reply.error("ERR wrong number of arguments for 'set' command"));
    }

    @Test
    void delCountsDeletedKeysAndRepeatedKeyCountsOnce() {
        run("SET", "a", "1");
        run("SET", "b", "2");

        assertThat(run("DEL", "a", "a", "c", "b")).isEqualTo(Reply.integer(2));
    }

    @Test
    void existsCountsEachArgumentSeparately() {
        run("SET", "a", "1");

        assertThat(run("EXISTS", "a", "a", "b")).isEqualTo(Reply.integer(2));
    }

    @Test
    void keysOnEmptyKeyspaceReturnsEmptyArray() {
        assertThat(run("KEYS", "*")).isEqualTo(Reply.array(List.of()));
    }

    @Test
    void keysReturnsOnlyMatchingKeys() {
        run("SET", "user:1", "x");
        run("SET", "user:2", "y");
        run("SET", "other", "z");

        Reply reply = run("KEYS", "user:*");

        assertThat(reply).isInstanceOf(Reply.ArrayReply.class);
        assertThat(((Reply.ArrayReply) reply).elements())
                .containsExactlyInAnyOrder(Reply.bulk(Bytes.utf8("user:1")), Reply.bulk(Bytes.utf8("user:2")));
    }

    @Test
    void dbsizeCountsKeysNotWrites() {
        run("SET", "a", "1");
        run("SET", "a", "2");

        assertThat(run("DBSIZE")).isEqualTo(Reply.integer(1));
    }

    @Test
    void flushdbClearsAllKeys() {
        run("SET", "a", "1");

        assertThat(run("FLUSHDB")).isEqualTo(Reply.OK);
        assertThat(run("DBSIZE")).isEqualTo(Reply.integer(0));
    }

    @Test
    void flushdbWithExtraArgumentIsSyntaxErrorAndKeepsKeys() {
        run("SET", "a", "1");

        assertThat(run("FLUSHDB", "extra")).isEqualTo(Reply.error("ERR syntax error"));
        assertThat(run("DBSIZE")).isEqualTo(Reply.integer(1));
    }

    @Test
    void unknownCommandWithoutArgumentsKeepsTheNameAsSent() {
        assertThat(run("FOO")).isEqualTo(Reply.error("ERR unknown command 'FOO', with args beginning with: "));
    }

    @Test
    void unknownCommandWithArgumentsListsThemQuoted() {
        assertThat(run("foo", "bar", "baz"))
                .isEqualTo(Reply.error("ERR unknown command 'foo', with args beginning with: 'bar' 'baz' "));
    }

    @Test
    void commandNameIsCaseInsensitive() {
        run("SET", "a", "1");

        assertThat(run("gEt", "a")).isEqualTo(Reply.bulk(Bytes.utf8("1")));
    }

    @Test
    void binaryValueRoundTrips() {
        Bytes binary = Bytes.of(new byte[] {'a', 0, (byte) 0xFF});

        dispatcher.dispatch(List.of(Bytes.utf8("SET"), Bytes.utf8("k"), binary));

        assertThat(dispatcher.dispatch(List.of(Bytes.utf8("GET"), Bytes.utf8("k"))))
                .isEqualTo(Reply.bulk(binary));
    }

    @Test
    void emptyArgvIsRejected() {
        assertThatThrownBy(() -> dispatcher.dispatch(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCommandReplacesLineBreaksWithSpaces() {
        assertThat(run("F\r\nOO", "a\r\nbc"))
                .isEqualTo(Reply.error("ERR unknown command 'F  OO', with args beginning with: 'a  bc' "));
    }

    @Test
    void unknownCommandCutsNameAndArgumentsAt128Bytes() {
        String longName = "y".repeat(200);
        assertThat(run(longName))
                .isEqualTo(Reply.error("ERR unknown command '" + "y".repeat(128) + "', with args beginning with: "));

        String longArg = "x".repeat(200);
        assertThat(run("FOO", longArg))
                .isEqualTo(
                        Reply.error("ERR unknown command 'FOO', with args beginning with: '" + "x".repeat(128) + "' "));

        String a60 = "a".repeat(60);
        String b60 = "b".repeat(60);
        String c60 = "c".repeat(60);
        assertThat(run("FOO", a60, b60, c60))
                .isEqualTo(Reply.error(
                        "ERR unknown command 'FOO', with args beginning with: '" + a60 + "' '" + b60 + "' 'cc' "));

        String x125 = "x".repeat(125);
        assertThat(run("FOO", x125, "y"))
                .isEqualTo(Reply.error("ERR unknown command 'FOO', with args beginning with: '" + x125 + "' "));
    }

    @Test
    void unknownCommandKeepsRawBytesAndZeroBytesInErrorText() {
        Reply nonAscii =
                dispatcher.dispatch(List.of(Bytes.utf8("FOO"), Bytes.of(new byte[] {(byte) 0xC3, (byte) 0xA9})));
        assertThat(nonAscii).isEqualTo(Reply.error("ERR unknown command 'FOO', with args beginning with: 'Ã©' "));

        Reply withNull = dispatcher.dispatch(List.of(Bytes.utf8("FOO"), Bytes.of(new byte[] {'a', 0, 'b'})));
        assertThat(withNull).isEqualTo(Reply.error("ERR unknown command 'FOO', with args beginning with: 'a\u0000b' "));
    }

    @Test
    void echoReturnsArgumentAsBulkStringAndChecksArity() {
        assertThat(run("ECHO", "hello")).isEqualTo(Reply.bulk(Bytes.utf8("hello")));
        assertThat(run("ECHO")).isEqualTo(Reply.error("ERR wrong number of arguments for 'echo' command"));
        assertThat(run("ECHO", "a", "b")).isEqualTo(Reply.error("ERR wrong number of arguments for 'echo' command"));
    }

    @Test
    void quitReturnsOkEvenWithExtraArguments() {
        assertThat(run("QUIT")).isEqualTo(Reply.OK);
        assertThat(run("quit", "extra")).isEqualTo(Reply.OK);
        assertThat(CommandTable.isQuit(List.of(Bytes.ascii("QuIt")))).isTrue();
    }

    @Test
    void dispatcherCatchesUnexpectedRuntimeExceptionAndLogsWithoutUserData() {
        CommandSpec boom = new CommandSpec("boom", 2, Set.of(Flag.READONLY), (ctx, argv) -> {
            throw new IllegalStateException("bug");
        });
        CommandTable table = new CommandTable(
                List.of(boom, new CommandSpec("ping", -1, Set.of(Flag.READONLY), (ctx, argv) -> Reply.PONG)));
        Dispatcher d = new Dispatcher(table, new CommandContext(Keyspaces.inMemory()));

        Logger logger = (Logger) LoggerFactory.getLogger(Dispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(d.dispatch(List.of(Bytes.ascii("BOOM"), Bytes.ascii("secret-val"))))
                    .isEqualTo(Reply.error("ERR internal error"));
            assertThat(d.dispatch(List.of(Bytes.ascii("PING")))).isEqualTo(Reply.PONG);
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getFormattedMessage()).contains("boom").doesNotContain("secret-val");
                assertThat(e.getThrowableProxy()).isNotNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private Reply run(String... parts) {
        return dispatcher.dispatch(Arrays.stream(parts).map(Bytes::utf8).toList());
    }
}
