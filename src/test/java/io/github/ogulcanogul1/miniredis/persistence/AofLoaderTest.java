package io.github.ogulcanogul1.miniredis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class AofLoaderTest {

    @Test
    void truncatesPartialTailWithWarnLogAndReplaysValidCommands(@TempDir Path dir) throws Exception {
        Path aof = dir.resolve("appendonly.aof");
        byte[] c1 = AofCodec.encodeCommand(List.of(Bytes.ascii("SET"), Bytes.ascii("k1"), Bytes.ascii("v1")));
        byte[] c2 = AofCodec.encodeCommand(List.of(Bytes.ascii("SET"), Bytes.ascii("k2"), Bytes.ascii("v2")));
        byte[] partial = new byte[c1.length + c2.length - 4];
        System.arraycopy(c1, 0, partial, 0, c1.length);
        System.arraycopy(c2, 0, partial, c1.length, c2.length - 4);
        Files.write(aof, partial);

        Logger logger = (Logger) LoggerFactory.getLogger(AofLoader.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            Keyspace ks = Keyspaces.inMemory();
            int replayed = AofLoader.load(
                    aof, RespLimits.defaults(), CommandTable.standard(), new CommandContext(ks, new ServerStats()));

            assertThat(replayed).isEqualTo(1);
            assertThat(ks.get(Bytes.ascii("k1"))).contains(Bytes.ascii("v1"));
            assertThat(ks.get(Bytes.ascii("k2"))).isEmpty();
            assertThat(Files.size(aof)).isEqualTo(c1.length);
            assertThat(logs.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("truncated partial tail"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void midFileCorruptionThrowsOnStartup(@TempDir Path dir) throws Exception {
        Path aof = dir.resolve("corrupt.aof");
        byte[] c1 = AofCodec.encodeCommand(List.of(Bytes.ascii("SET"), Bytes.ascii("k1"), Bytes.ascii("v1")));
        byte[] bad = "*bad\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] combined = new byte[c1.length + bad.length];
        System.arraycopy(c1, 0, combined, 0, c1.length);
        System.arraycopy(bad, 0, combined, c1.length, bad.length);
        Files.write(aof, combined);

        Keyspace ks = Keyspaces.inMemory();
        assertThatThrownBy(() -> AofLoader.load(
                        aof, RespLimits.defaults(), CommandTable.standard(), new CommandContext(ks, new ServerStats())))
                .isInstanceOf(AofException.class);
    }
}
