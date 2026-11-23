package io.github.ogulcanogul1.miniredis.commands;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the command table (Command pattern, table-driven dispatch).
 *
 * @param name the command name in lower case, e.g. {@code "get"}
 * @param arity as in Redis, counting the command name: positive means exactly that many
 *     arguments, negative means at least {@code |arity|}. Never 0.
 * @param flags properties of the command. Faz 8 uses {@link Flag#WRITE} to pick what goes to the
 *     AOF.
 * @param handler the code
 */
public record CommandSpec(String name, int arity, Set<Flag> flags, CommandHandler handler) {

    /** Command properties. */
    public enum Flag {
        /** May change the keyspace. */
        WRITE,
        /** Never changes the keyspace. */
        READONLY
    }

    public CommandSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("command name must be lower case: " + name);
        }
        if (arity == 0) {
            throw new IllegalArgumentException("arity must not be 0: " + name);
        }
        flags = Set.copyOf(flags);
    }

    /** Returns whether {@code argc} (the size of argv, name included) fits the arity. */
    public boolean acceptsArgCount(int argc) {
        return arity > 0 ? argc == arity : argc >= -arity;
    }
}
