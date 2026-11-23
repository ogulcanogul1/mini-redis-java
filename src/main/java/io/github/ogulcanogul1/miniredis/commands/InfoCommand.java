package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Handler for {@code INFO [section]} returning Redis-style key:value sections. */
final class InfoCommand {

    private InfoCommand() {}

    static Reply info(CommandContext ctx, List<Bytes> argv) {
        if (argv.size() > 2) {
            return Errors.wrongArity("info");
        }
        String section =
                argv.size() == 2 ? argv.get(1).decode(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT) : "default";
        boolean all = section.equals("default") || section.equals("all") || section.equals("everything");

        StringBuilder sb = new StringBuilder(512);
        if (all || section.equals("server")) {
            appendServer(sb, ctx.stats());
        }
        if (all || section.equals("clients")) {
            appendClients(sb, ctx.stats());
        }
        if (all || section.equals("memory")) {
            appendMemory(sb, ctx);
        }
        if (all || section.equals("stats")) {
            appendStats(sb, ctx.stats());
        }
        if (all || section.equals("persistence")) {
            appendPersistence(sb, ctx.stats());
        }
        return Reply.bulk(Bytes.utf8(sb.toString()));
    }

    private static void appendServer(StringBuilder sb, ServerStats stats) {
        if (!sb.isEmpty()) {
            sb.append("\r\n");
        }
        sb.append("# Server\r\n");
        sb.append("redis_version:mini-redis\r\n");
        sb.append("os:").append(System.getProperty("os.name", "unknown")).append("\r\n");
        sb.append("java_version:").append(Runtime.version().toString()).append("\r\n");
        sb.append("uptime_in_seconds:").append(stats.uptimeSeconds()).append("\r\n");
    }

    private static void appendClients(StringBuilder sb, ServerStats stats) {
        if (!sb.isEmpty()) {
            sb.append("\r\n");
        }
        sb.append("# Clients\r\n");
        sb.append("connected_clients:").append(stats.connectedClients()).append("\r\n");
        sb.append("rejected_connections:").append(stats.rejectedClients()).append("\r\n");
    }

    private static void appendMemory(StringBuilder sb, CommandContext ctx) {
        if (!sb.isEmpty()) {
            sb.append("\r\n");
        }
        Runtime rt = Runtime.getRuntime();
        long jvmUsed = rt.totalMemory() - rt.freeMemory();
        sb.append("# Memory\r\n");
        sb.append("used_memory:").append(ctx.keyspace().usedMemoryBytes()).append("\r\n");
        sb.append("jvm_used_memory:").append(jvmUsed).append("\r\n");
        sb.append("jvm_max_memory:").append(rt.maxMemory()).append("\r\n");
    }

    private static void appendStats(StringBuilder sb, ServerStats stats) {
        if (!sb.isEmpty()) {
            sb.append("\r\n");
        }
        sb.append("# Stats\r\n");
        sb.append("total_connections_received:")
                .append(stats.totalConnectionsReceived())
                .append("\r\n");
        sb.append("total_commands_processed:").append(stats.totalCommands()).append("\r\n");
        sb.append("total_error_replies:").append(stats.totalErrorReplies()).append("\r\n");
    }

    private static void appendPersistence(StringBuilder sb, ServerStats stats) {
        if (!sb.isEmpty()) {
            sb.append("\r\n");
        }
        sb.append("# Persistence\r\n");
        sb.append("aof_enabled:").append(stats.aofEnabled() ? 1 : 0).append("\r\n");
        sb.append("rdb_changes_since_last_save:")
                .append(stats.rdbChangesSinceLastSave())
                .append("\r\n");
        sb.append("rdb_last_save_time:").append(stats.rdbLastSaveEpochSeconds()).append("\r\n");
    }
}
