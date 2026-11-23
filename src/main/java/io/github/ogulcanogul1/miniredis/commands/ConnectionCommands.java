package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;

/** Connection commands: {@code PING}, {@code ECHO}, {@code QUIT}. */
final class ConnectionCommands {

    private ConnectionCommands() {}

    /** {@code PING [message]}: {@code +PONG}, or the message as a bulk string. At most one argument. */
    static Reply ping(CommandContext ctx, List<Bytes> argv) {
        return switch (argv.size()) {
            case 1 -> Reply.PONG;
            case 2 -> Reply.bulk(argv.get(1));
            default -> Errors.wrongArity("ping");
        };
    }

    /** {@code ECHO message}: returns {@code message} as a bulk string. */
    static Reply echo(CommandContext ctx, List<Bytes> argv) {
        return Reply.bulk(argv.get(1));
    }

    /** {@code QUIT}: always returns {@code +OK}. The session closes the connection right after. */
    static Reply quit(CommandContext ctx, List<Bytes> argv) {
        return Reply.OK;
    }
}
