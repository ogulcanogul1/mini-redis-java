package io.github.ogulcanogul1.miniredis;

import io.github.ogulcanogul1.miniredis.server.ConfigException;
import io.github.ogulcanogul1.miniredis.server.ConfigParser;
import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point. Parses CLI/config arguments, starts {@link RedisServer}, and handles shutdown. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int run(String[] args) {
        if (args.length == 1 && ("--version".equals(args[0]) || "-v".equals(args[0]))) {
            System.out.println("mini-redis " + version());
            return 0;
        }
        ServerConfig config;
        try {
            config = ConfigParser.parseArgs(args);
        } catch (ConfigException e) {
            log.error("invalid configuration: {}", e.getMessage());
            return 2;
        }
        try (RedisServer server = new RedisServer(config)) {
            Thread shutdownHook = new Thread(server::stop, "shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.start();
                server.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down; the hook is running.
                }
            }
            return 0;
        } catch (IOException e) {
            log.error("failed to start server on {}:{}: {}", config.bind(), config.port(), e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    static String version() {
        return RedisServer.version();
    }
}
