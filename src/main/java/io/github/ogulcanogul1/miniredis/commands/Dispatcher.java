package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Finds the command for an argv, checks its arity and runs it. */
public final class Dispatcher {

    private final CommandTable table;
    private final CommandContext ctx;

    public Dispatcher(CommandTable table, CommandContext ctx) {
        this.table = Objects.requireNonNull(table, "table");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /**
     * Runs {@code argv}.
     *
     * @param argv the command name followed by its arguments
     * @return the command's reply, or an error reply for an unknown command or a wrong number of
     *     arguments (texts as in Redis 7)
     * @throws IllegalArgumentException if {@code argv} is empty (the parser never produces one)
     */
    public Reply dispatch(List<Bytes> argv) {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        Optional<CommandSpec> found = table.lookup(argv.getFirst());
        if (found.isEmpty()) {
            return Errors.unknownCommand(argv);
        }
        CommandSpec spec = found.get();
        if (!spec.acceptsArgCount(argv.size())) {
            return Errors.wrongArity(spec.name());
        }
        return spec.handler().execute(ctx, argv);
    }
}
