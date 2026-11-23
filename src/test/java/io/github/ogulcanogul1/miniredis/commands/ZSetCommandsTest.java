package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZSetCommandsTest {

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
    void zaddZscoreZrangeZrangeByScoreAndZrank() {
        Dispatcher d = dispatcher();
        assertThat(run(d, "ZADD", "z", "20", "bob", "10", "alice", "20", "adam"))
                .isEqualTo(Reply.integer(3));
        assertThat(run(d, "ZADD", "z", "5", "bob")).isEqualTo(Reply.integer(0));
        assertThat(run(d, "ZSCORE", "z", "bob")).isEqualTo(bulk("5"));
        assertThat(run(d, "ZSCORE", "z", "none")).isEqualTo(Reply.NIL);

        assertThat(run(d, "ZRANGE", "z", "0", "-1"))
                .isEqualTo(Reply.array(List.of(bulk("bob"), bulk("alice"), bulk("adam"))));
        assertThat(run(d, "ZRANGE", "z", "0", "1", "WITHSCORES"))
                .isEqualTo(Reply.array(List.of(bulk("bob"), bulk("5"), bulk("alice"), bulk("10"))));
        assertThat(run(d, "ZRANGEBYSCORE", "z", "6", "+inf"))
                .isEqualTo(Reply.array(List.of(bulk("alice"), bulk("adam"))));
        assertThat(run(d, "ZRANK", "z", "alice")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "ZRANK", "z", "none")).isEqualTo(Reply.NIL);
    }
}
