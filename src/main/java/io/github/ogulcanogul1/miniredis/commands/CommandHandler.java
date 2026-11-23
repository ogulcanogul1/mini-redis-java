package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;

/** The code of one command. Registered as a method reference, e.g. {@code StringCommands::get}. */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Runs the command. The only side effect allowed is changing the keyspace. No I/O.
     *
     * @param ctx what the command may use
     * @param argv the command name followed by its arguments. The dispatcher has already checked
     *     the arity.
     * @return the reply, never {@code null}. Wrong usage by the client is an {@link
     *     Reply.ErrorReply}, not an exception (K12).
     */
    Reply execute(CommandContext ctx, List<Bytes> argv);
}
