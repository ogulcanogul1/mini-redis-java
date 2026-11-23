package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListCommandsTest {

    private static Dispatcher dispatcher() {
        Keyspace ks = Keyspaces.inMemory();
        return new Dispatcher(CommandTable.standard(), new CommandContext(ks, new ServerStats()));
    }

    private static Reply run(Dispatcher d, String... args) {
        return d.dispatch(Arrays.stream(args).map(Bytes::ascii).toList());
    }

    private static Reply bulk(String s) {
        return Reply.bulk(Bytes.ascii(s));
    }

    @Test
    void pushPopRangeLenAndWrongType() {
        Dispatcher d = dispatcher();
        assertThat(run(d, "LLEN", "list")).isEqualTo(Reply.integer(0));
        assertThat(run(d, "LPOP", "list")).isEqualTo(Reply.NIL);

        assertThat(run(d, "RPUSH", "list", "b", "c")).isEqualTo(Reply.integer(2));
        assertThat(run(d, "LPUSH", "list", "a")).isEqualTo(Reply.integer(3));
        assertThat(run(d, "LLEN", "list")).isEqualTo(Reply.integer(3));
        assertThat(run(d, "LRANGE", "list", "0", "-1"))
                .isEqualTo(Reply.array(List.of(bulk("a"), bulk("b"), bulk("c"))));

        assertThat(run(d, "GET", "list")).isInstanceOf(Reply.ErrorReply.class);
        assertThat(((Reply.ErrorReply) run(d, "GET", "list")).message()).startsWith("WRONGTYPE");

        assertThat(run(d, "LPOP", "list")).isEqualTo(bulk("a"));
        assertThat(run(d, "RPOP", "list")).isEqualTo(bulk("c"));
        assertThat(run(d, "LPOP", "list")).isEqualTo(bulk("b"));
        assertThat(run(d, "EXISTS", "list")).isEqualTo(Reply.integer(0));
    }
}
