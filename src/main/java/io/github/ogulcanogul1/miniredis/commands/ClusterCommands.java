package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.ClusterSlots;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Handlers for {@code CLUSTER KEYSLOT} and {@code CLUSTER INFO}. */
final class ClusterCommands {

    private ClusterCommands() {}

    static Reply cluster(CommandContext ctx, List<Bytes> argv) {
        String sub = argv.get(1).decode(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "keyslot" -> {
                if (argv.size() != 3) {
                    yield wrongSubcommandArgs(sub);
                }
                yield Reply.integer(ClusterSlots.keySlot(argv.get(2).toByteArray()));
            }
            case "info" -> {
                if (argv.size() != 2) {
                    yield wrongSubcommandArgs(sub);
                }
                String info = "cluster_state:ok\r\n"
                        + "cluster_slots_assigned:16384\r\n"
                        + "cluster_slots_ok:16384\r\n"
                        + "cluster_slots_pfail:0\r\n"
                        + "cluster_slots_fail:0\r\n"
                        + "cluster_known_nodes:1\r\n"
                        + "cluster_size:1\r\n";
                yield Reply.bulk(Bytes.utf8(info));
            }
            default -> wrongSubcommandArgs(sub);
        };
    }

    private static Reply wrongSubcommandArgs(String sub) {
        return new Reply.ErrorReply(
                "ERR Unknown subcommand or wrong number of arguments for '" + sub + "'. Try CLUSTER HELP.");
    }
}
