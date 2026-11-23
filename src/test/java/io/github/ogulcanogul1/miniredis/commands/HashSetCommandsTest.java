package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HashSetCommandsTest {

    private static Dispatcher dispatcher() {
        return new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory(), new ServerStats()));
    }

    private static Reply run(Dispatcher d, String... args) {
        return d.dispatch(Arrays.stream(args).map(Bytes::ascii).toList());
    }

    private static Reply bulk(String s) {
        return Reply.bulk(Bytes.ascii(s));
    }

    @Test
    void hashCommandsAndEmptyKeyCleanup() {
        Dispatcher d = dispatcher();
        assertThat(run(d, "HSET", "h", "f1", "v1", "f2", "v2")).isEqualTo(Reply.integer(2));
        assertThat(run(d, "HSET", "h", "f1", "v1b")).isEqualTo(Reply.integer(0));
        assertThat(run(d, "HGET", "h", "f1")).isEqualTo(bulk("v1b"));
        assertThat(run(d, "HGET", "h", "missing")).isEqualTo(Reply.NIL);
        assertThat(((Reply.ArrayReply) run(d, "HGETALL", "h")).elements()).hasSize(4);

        assertThat(run(d, "HDEL", "h", "f1", "f2", "none")).isEqualTo(Reply.integer(2));
        assertThat(run(d, "EXISTS", "h")).isEqualTo(Reply.integer(0));
    }

    @Test
    void setCommandsAndWrongType() {
        Dispatcher d = dispatcher();
        assertThat(run(d, "SADD", "s", "a", "b", "a")).isEqualTo(Reply.integer(2));
        assertThat(run(d, "SISMEMBER", "s", "a")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "SISMEMBER", "s", "z")).isEqualTo(Reply.integer(0));
        assertThat(((Reply.ArrayReply) run(d, "SMEMBERS", "s")).elements())
                .containsExactlyInAnyOrder(bulk("a"), bulk("b"));

        assertThat(((Reply.ErrorReply) run(d, "HGET", "s", "a")).message()).startsWith("WRONGTYPE");
        assertThat(run(d, "SREM", "s", "a", "b")).isEqualTo(Reply.integer(2));
        assertThat(run(d, "EXISTS", "s")).isEqualTo(Reply.integer(0));
    }
}
