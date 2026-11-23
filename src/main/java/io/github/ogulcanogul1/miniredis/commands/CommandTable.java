package io.github.ogulcanogul1.miniredis.commands;

import static io.github.ogulcanogul1.miniredis.commands.CommandSpec.Flag.READONLY;
import static io.github.ogulcanogul1.miniredis.commands.CommandSpec.Flag.WRITE;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.*;

/** Looks commands up by name, case-insensitively. Immutable after construction. */
public class CommandTable {
    private final Map<Bytes, CommandSpec> byName;

    /**
     * Creates a table from {@code specs}.
     *
     * @throws IllegalArgumentException if two specs have the same name
     */
    public CommandTable(List<CommandSpec> specs) {
        Map<Bytes, CommandSpec> map = new HashMap<>();
        for (CommandSpec spec : specs) {
            if (map.put(Bytes.ascii(spec.name()), spec) != null) {
                throw new IllegalArgumentException("duplicate command: " + spec.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    /** Returns the table with every command of the current phase (K11). */
    public static CommandTable standard() {
        return new CommandTable(List.of(
                new CommandSpec("ping", -1, Set.of(READONLY), ConnectionCommands::ping),
                new CommandSpec("set", 3, Set.of(WRITE), StringCommands::set),
                new CommandSpec("get", 2, Set.of(READONLY), StringCommands::get),
                new CommandSpec("del", -2, Set.of(WRITE), KeyCommands::del),
                new CommandSpec("exists", -2, Set.of(READONLY), KeyCommands::exists),
                new CommandSpec("keys", 2, Set.of(READONLY), KeyCommands::keys),
                new CommandSpec("dbsize", 1, Set.of(READONLY), KeyCommands::dbsize),
                new CommandSpec("flushdb", -1, Set.of(WRITE), KeyCommands::flushdb)));
    }

    /** Finds the command called {@code name}, ignoring ASCII case. */
    public Optional<CommandSpec> lookup(Bytes name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(byName.get(name.toLowerAscii()));
    }
}
