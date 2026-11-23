package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import java.util.Objects;

/**
 * What a command may use while it runs (keyspace and shared {@link ServerStats}). Later phases add
 * clock / persistence hooks without changing command handler signatures.
 */
public record CommandContext(Keyspace keyspace, ServerStats stats) {

    public CommandContext {
        Objects.requireNonNull(keyspace, "keyspace");
        Objects.requireNonNull(stats, "stats");
    }

    public CommandContext(Keyspace keyspace) {
        this(keyspace, new ServerStats());
    }
}
