package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import java.util.Objects;

/**
 * What a command may use while it runs (keyspace and shared {@link ServerStats}). Later phases add
 * clock / persistence hooks without changing command handler signatures.
 */
public record CommandContext(Keyspace keyspace, ServerStats stats, SnapshotTrigger snapshotTrigger) {

    public CommandContext {
        Objects.requireNonNull(keyspace, "keyspace");
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(snapshotTrigger, "snapshotTrigger");
    }

    public CommandContext(Keyspace keyspace, ServerStats stats) {
        this(keyspace, stats, SnapshotTrigger.NOOP);
    }

    public CommandContext(Keyspace keyspace) {
        this(keyspace, new ServerStats(), SnapshotTrigger.NOOP);
    }
}
