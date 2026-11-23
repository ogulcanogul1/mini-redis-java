package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.EvictionPolicy;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EvictionCommandsTest {

    private static Reply run(Dispatcher d, String... args) {
        return d.dispatch(Arrays.stream(args).map(Bytes::ascii).toList());
    }

    @Test
    void noevictionReturnsOomOnWriteWhileAllowingReadsAndDeletes() {
        Keyspace ks = Keyspaces.inMemory();
        ks.set(Bytes.ascii("k1"), Bytes.ascii("v1"));
        long tightLimit = ks.usedMemoryBytes() - 1;

        Dispatcher d = new Dispatcher(
                CommandTable.standard(),
                new CommandContext(ks, new ServerStats()),
                (s, a, r) -> {},
                tightLimit,
                EvictionPolicy.NOEVICTION);

        Reply err = run(d, "SET", "k2", "v2");
        assertThat(err).isEqualTo(Reply.error("OOM command not allowed when used memory > 'maxmemory'."));
        assertThat(run(d, "GET", "k1")).isEqualTo(Reply.bulk(Bytes.ascii("v1")));
        assertThat(run(d, "DEL", "k1")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "SET", "k2", "v2")).isEqualTo(Reply.OK);
    }

    @Test
    void allkeysLruAllowsNewWritesUnderMaxMemory() {
        Keyspace ks = Keyspaces.inMemory();
        ks.set(Bytes.ascii("k1"), Bytes.ascii("v1"));
        long oneKeyBudget = ks.usedMemoryBytes();

        Dispatcher d = new Dispatcher(
                CommandTable.standard(),
                new CommandContext(ks, new ServerStats()),
                (s, a, r) -> {},
                oneKeyBudget,
                EvictionPolicy.ALLKEYS_LRU);

        assertThat(run(d, "SET", "k2", "v2")).isEqualTo(Reply.OK);
        assertThat(ks.usedMemoryBytes()).isLessThanOrEqualTo(oneKeyBudget);
    }
}
