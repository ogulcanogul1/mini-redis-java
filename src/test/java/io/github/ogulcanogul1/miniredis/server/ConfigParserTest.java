package io.github.ogulcanogul1.miniredis.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.LockType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigParserTest {

    @Test
    void defaultsUseLocalhost6380ThreadPoolNotice() {
        ServerConfig cfg = ServerConfig.defaults();

        assertThat(cfg.bind()).isEqualTo("127.0.0.1");
        assertThat(cfg.port()).isEqualTo(6380);
        assertThat(cfg.ioModel()).isEqualTo(ServerConfig.IoModel.THREAD_POOL);
        assertThat(cfg.logLevel()).isEqualTo(ServerConfig.LogLevel.NOTICE);
        assertThat(cfg.respLimits()).isEqualTo(RespLimits.defaults());
        assertThat(cfg.workerThreads()).isEqualTo(8);
        assertThat(cfg.queueCapacity()).isEqualTo(256);
        assertThat(cfg.shards()).isEqualTo(16);
        assertThat(cfg.lockType()).isEqualTo(LockType.RWLOCK);
    }

    @Test
    void parsesLinesSkippingBlankLinesAndComments() {
        ServerConfig cfg = ConfigParser.parseLines(List.of(
                "",
                "  # comment line",
                "bind 0.0.0.0",
                "port 6381",
                "io-model thread-per-conn",
                "loglevel debug",
                "proto-max-bulk-len 1048576",
                "worker-threads 12",
                "queue-capacity 64",
                "shards 4",
                "lock-type mutex"));

        assertThat(cfg.bind()).isEqualTo("0.0.0.0");
        assertThat(cfg.port()).isEqualTo(6381);
        assertThat(cfg.ioModel()).isEqualTo(ServerConfig.IoModel.THREAD_PER_CONN);
        assertThat(cfg.logLevel()).isEqualTo(ServerConfig.LogLevel.DEBUG);
        assertThat(cfg.respLimits().maxBulkLength()).isEqualTo(1048576);
        assertThat(cfg.workerThreads()).isEqualTo(12);
        assertThat(cfg.queueCapacity()).isEqualTo(64);
        assertThat(cfg.shards()).isEqualTo(4);
        assertThat(cfg.lockType()).isEqualTo(LockType.MUTEX);
        assertThat(ConfigParser.parseLines(List.of("io-model SINGLE")).ioModel())
                .isEqualTo(ServerConfig.IoModel.SINGLE);
        assertThat(ConfigParser.parseLines(List.of("io-model thread-pool")).ioModel())
                .isEqualTo(ServerConfig.IoModel.THREAD_POOL);
    }

    @Test
    void readsFileAndAppliesCliOverrides(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("redis.conf");
        Files.writeString(file, "port 6399\nloglevel warning\n", StandardCharsets.UTF_8);

        ServerConfig fromFile = ConfigParser.parseFile(file);
        assertThat(fromFile.port()).isEqualTo(6399);
        assertThat(fromFile.logLevel()).isEqualTo(ServerConfig.LogLevel.WARNING);

        ServerConfig overridden =
                ConfigParser.parseArgs(new String[] {file.toString(), "--port", "7000", "--loglevel", "verbose"});
        assertThat(overridden.port()).isEqualTo(7000);
        assertThat(overridden.logLevel()).isEqualTo(ServerConfig.LogLevel.VERBOSE);
        assertThat(overridden.bind()).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsInvalidConfigsWithConfigException(@TempDir Path tempDir) {
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("unknown 1"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("port"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("port abc"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("port -1"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("port 65536"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("io-model virtual")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("loglevel trace")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("proto-max-bulk-len 0")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() ->
                        ConfigParser.parseLines(List.of("proto-max-bulk-len " + (RespLimits.MAX_BULK_LENGTH_CAP + 1))))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseArgs(new String[] {"--port"}))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseFile(tempDir.resolve("missing.conf")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> new ServerConfig(
                        "  ", 6380, ServerConfig.IoModel.SINGLE, ServerConfig.LogLevel.NOTICE, RespLimits.defaults()))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("shards 3"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("worker-threads 0")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("queue-capacity 0")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> ConfigParser.parseLines(List.of("lock-type spin")))
                .isInstanceOf(ConfigException.class);
    }
}
