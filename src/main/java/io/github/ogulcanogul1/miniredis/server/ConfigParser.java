package io.github.ogulcanogul1.miniredis.server;

import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parses Redis-style {@code key value} configuration lines, files, and CLI overrides. Any unknown
 * key, missing value, or invalid number fails fast with {@link ConfigException} (K12).
 */
public final class ConfigParser {

    private ConfigParser() {}

    /** Parses configuration lines starting from {@link ServerConfig#defaults()}. */
    public static ServerConfig parseLines(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        Builder builder = new Builder(ServerConfig.defaults());
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = Objects.requireNonNull(lines.get(i), "line").trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                throw new ConfigException("missing value for key '" + parts[0] + "' at line " + (i + 1));
            }
            builder.apply(parts[0], parts[1].trim());
        }
        return builder.build();
    }

    /** Reads and parses a UTF-8 configuration file. */
    public static ServerConfig parseFile(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return parseLines(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException("failed to read config file: " + path, e);
        }
    }

    /**
     * Parses CLI arguments: an optional config file path (when {@code args[0]} does not start with
     * {@code --}), followed by {@code --key value} pairs that override file or default settings.
     */
    public static ServerConfig parseArgs(String[] args) {
        Objects.requireNonNull(args, "args");
        int i = 0;
        ServerConfig base = ServerConfig.defaults();
        if (args.length > 0) {
            String first = Objects.requireNonNull(args[0], "arg");
            if (!first.startsWith("--")) {
                base = parseFile(Path.of(first));
                i = 1;
            }
        }
        Builder builder = new Builder(base);
        while (i < args.length) {
            String opt = Objects.requireNonNull(args[i], "arg");
            if (!opt.startsWith("--") || opt.length() == 2) {
                throw new ConfigException("expected --key value, got: " + opt);
            }
            if (i + 1 >= args.length) {
                throw new ConfigException("missing value for option: " + opt);
            }
            String val = Objects.requireNonNull(args[i + 1], "arg value");
            builder.apply(opt.substring(2), val);
            i += 2;
        }
        return builder.build();
    }

    private static final class Builder {
        private String bind;
        private int port;
        private ServerConfig.IoModel ioModel;
        private ServerConfig.LogLevel logLevel;
        private RespLimits respLimits;

        Builder(ServerConfig base) {
            this.bind = base.bind();
            this.port = base.port();
            this.ioModel = base.ioModel();
            this.logLevel = base.logLevel();
            this.respLimits = base.respLimits();
        }

        void apply(String rawKey, String value) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            switch (key) {
                case "bind" -> {
                    if (value.isBlank()) {
                        throw new ConfigException("bind must not be blank");
                    }
                    this.bind = value;
                }
                case "port" -> this.port = parseInt(key, value);
                case "io-model" ->
                    this.ioModel = switch (value.toLowerCase(Locale.ROOT)) {
                        case "single" -> ServerConfig.IoModel.SINGLE;
                        default -> throw new ConfigException("unsupported io-model: " + value);
                    };
                case "loglevel" ->
                    this.logLevel = switch (value.toLowerCase(Locale.ROOT)) {
                        case "debug" -> ServerConfig.LogLevel.DEBUG;
                        case "verbose" -> ServerConfig.LogLevel.VERBOSE;
                        case "notice" -> ServerConfig.LogLevel.NOTICE;
                        case "warning" -> ServerConfig.LogLevel.WARNING;
                        default -> throw new ConfigException("invalid loglevel: " + value);
                    };
                case "proto-max-bulk-len" -> {
                    int maxBulk = parseInt(key, value);
                    try {
                        this.respLimits =
                                new RespLimits(respLimits.maxArrayLength(), maxBulk, respLimits.maxLineLength());
                    } catch (IllegalArgumentException e) {
                        throw new ConfigException("invalid proto-max-bulk-len: " + e.getMessage(), e);
                    }
                }
                default -> throw new ConfigException("unknown config key: " + rawKey);
            }
        }

        ServerConfig build() {
            return new ServerConfig(bind, port, ioModel, logLevel, respLimits);
        }

        private static int parseInt(String key, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new ConfigException("invalid integer for '" + key + "': " + value, e);
            }
        }
    }
}
