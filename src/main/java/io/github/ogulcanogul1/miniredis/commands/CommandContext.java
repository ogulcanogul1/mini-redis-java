package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import java.util.Objects;

/**
 * What a command may use while it runs. Faz 1 has only the keyspace. Later phases add fields here
 * (the clock in Faz 6, the client in Faz 3), so handler signatures stay the same.
 */
public record CommandContext(Keyspace keyspace) {
    public CommandContext {
        Objects.requireNonNull(keyspace, "keyspace");
    }
}
