package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private Reply run(String... parts) {
        return dispatcher.dispatch(Arrays.stream(parts).map(Bytes::utf8).toList());
    }
}
