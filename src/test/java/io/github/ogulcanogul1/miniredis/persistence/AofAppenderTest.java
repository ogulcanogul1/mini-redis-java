package io.github.ogulcanogul1.miniredis.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.Reply;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AofAppenderTest {

    private static final Clock FIXED_CLOCK = new Clock() {
        @Override
        public long millis() {
            return 1_000_000L;
        }

        @Override
        public long nanoTime() {
            return 1_000_000_000L;
        }
    };

    private static final class InMemoryAofChannel implements AofChannel {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final AtomicInteger forceCount = new AtomicInteger();
        final AtomicBoolean failOnWrite = new AtomicBoolean();

        @Override
        public void writeAll(ByteBuffer src) throws IOException {
            if (failOnWrite.get()) {
                throw new IOException("simulated disk full (ENOSPC)");
            }
            byte[] chunk = new byte[src.remaining()];
            src.get(chunk);
            data.writeBytes(chunk);
        }

        @Override
        public void force(boolean metaData) {
            forceCount.incrementAndGet();
        }

        @Override
        public long size() {
            return data.size();
        }

        @Override
        public int read(ByteBuffer dst, long position) {
            byte[] bytes = data.toByteArray();
            if (position >= bytes.length) {
                return -1;
            }
            int len = Math.min(dst.remaining(), bytes.length - (int) position);
            dst.put(bytes, (int) position, len);
            return len;
        }

        @Override
        public void truncate(long size) {
            byte[] bytes = data.toByteArray();
            data.reset();
            data.write(bytes, 0, (int) size);
        }

        @Override
        public void close() {}
    }

    @Test
    void alwaysPolicyForcesEveryWriteAndTranslatesRelativeTtlToPexpireAt() throws Exception {
        InMemoryAofChannel ch = new InMemoryAofChannel();
        try (AofAppender appender = new AofAppender(ch, AofFsyncPolicy.ALWAYS, FIXED_CLOCK)) {
            Dispatcher d = new Dispatcher(
                    CommandTable.standard(),
                    new CommandContext(Keyspaces.inMemory(FIXED_CLOCK), new ServerStats()),
                    appender);

            assertThat(d.dispatch(List.of(
                            Bytes.ascii("SET"),
                            Bytes.ascii("k"),
                            Bytes.ascii("v"),
                            Bytes.ascii("EX"),
                            Bytes.ascii("10"))))
                    .isEqualTo(Reply.OK);
            assertThat(d.dispatch(List.of(Bytes.ascii("SET"), Bytes.ascii("k"), Bytes.ascii("v2"), Bytes.ascii("NX"))))
                    .isEqualTo(Reply.NIL);
            assertThat(ch.forceCount.get()).isEqualTo(1);

            AofCodec.DecodeResult decoded = AofCodec.decodeAll(ch.data.toByteArray(), RespLimits.defaults());
            assertThat(decoded.commands())
                    .containsExactly(
                            List.of(Bytes.ascii("SET"), Bytes.ascii("k"), Bytes.ascii("v")),
                            List.of(Bytes.ascii("PEXPIREAT"), Bytes.ascii("k"), Bytes.ascii("1010000")));
        }
    }

    @Test
    void writeFailureLogsErrorAndDispatcherReturnsInternalError() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AofAppender.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            InMemoryAofChannel ch = new InMemoryAofChannel();
            ch.failOnWrite.set(true);
            try (AofAppender appender = new AofAppender(ch, AofFsyncPolicy.NO, FIXED_CLOCK)) {
                Dispatcher d = new Dispatcher(
                        CommandTable.standard(),
                        new CommandContext(Keyspaces.inMemory(FIXED_CLOCK), new ServerStats()),
                        appender);
                Reply reply = d.dispatch(List.of(Bytes.ascii("SET"), Bytes.ascii("k"), Bytes.ascii("v")));
                assertThat(reply).isEqualTo(Reply.error("ERR internal error"));
                assertThat(logs.list)
                        .anyMatch(e -> e.getLevel() == Level.ERROR
                                && e.getFormattedMessage().contains("AOF write/fsync failed"));
            }
        } finally {
            logger.detachAppender(logs);
        }
    }
}
