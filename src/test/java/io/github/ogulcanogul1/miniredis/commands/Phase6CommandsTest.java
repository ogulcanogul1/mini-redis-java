package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class Phase6CommandsTest {

    private static final class FakeClock implements Clock {
        final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public long nanoTime() {
            return millis.get() * 1_000_000L;
        }
    }

    private static Dispatcher dispatcher(Clock clock) {
        Keyspace ks = Keyspaces.inMemory(clock);
        return new Dispatcher(CommandTable.standard(), new CommandContext(ks, new ServerStats()));
    }

    private static Reply run(Dispatcher d, String... args) {
        List<Bytes> argv = Arrays.stream(args).map(Bytes::ascii).toList();
        return d.dispatch(argv);
    }

    @Test
    void setOptionsAndErrorsMatchRedis() {
        FakeClock clock = new FakeClock();
        Dispatcher d = dispatcher(clock);

        assertThat(run(d, "SET", "k", "v", "EX", "10")).isEqualTo(Reply.OK);
        assertThat(run(d, "TTL", "k")).isEqualTo(Reply.integer(10));
        assertThat(run(d, "PTTL", "k")).isEqualTo(Reply.integer(10_000));

        assertThat(run(d, "SET", "k", "v2", "NX")).isEqualTo(Reply.NIL);
        assertThat(run(d, "SET", "k", "v2", "XX", "PX", "5000")).isEqualTo(Reply.OK);
        assertThat(run(d, "PTTL", "k")).isEqualTo(Reply.integer(5_000));

        assertThat(run(d, "SET", "k", "v", "EX", "0"))
                .isEqualTo(Reply.error("ERR invalid expire time in 'set' command"));
        assertThat(run(d, "SET", "k", "v", "EX", "10", "PX", "100")).isEqualTo(Reply.error("ERR syntax error"));
        assertThat(run(d, "SET", "k", "v", "NX", "XX")).isEqualTo(Reply.error("ERR syntax error"));
        assertThat(run(d, "SET", "k", "v", "EX", "abc"))
                .isEqualTo(Reply.error("ERR value is not an integer or out of range"));
    }

    @Test
    void expirePexpirePexpireAtPersistUnlink() {
        FakeClock clock = new FakeClock();
        Dispatcher d = dispatcher(clock);

        assertThat(run(d, "TTL", "missing")).isEqualTo(Reply.integer(-2));
        assertThat(run(d, "EXPIRE", "missing", "10")).isEqualTo(Reply.integer(0));

        run(d, "SET", "k", "v");
        assertThat(run(d, "TTL", "k")).isEqualTo(Reply.integer(-1));
        assertThat(run(d, "EXPIRE", "k", "20")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "TTL", "k")).isEqualTo(Reply.integer(20));

        assertThat(run(d, "PERSIST", "k")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "TTL", "k")).isEqualTo(Reply.integer(-1));
        assertThat(run(d, "PERSIST", "k")).isEqualTo(Reply.integer(0));

        assertThat(run(d, "PEXPIREAT", "k", "1002500")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "PTTL", "k")).isEqualTo(Reply.integer(2500));

        assertThat(run(d, "UNLINK", "k", "k", "missing")).isEqualTo(Reply.integer(1));
        assertThat(run(d, "GET", "k")).isEqualTo(Reply.NIL);
    }
}
