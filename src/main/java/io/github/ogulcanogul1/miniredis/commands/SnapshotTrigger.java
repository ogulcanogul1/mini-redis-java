package io.github.ogulcanogul1.miniredis.commands;

/**
 * Persistence hook invoked by {@code SAVE} and {@code BGSAVE} without coupling the {@code commands}
 * layer to {@code persistence} (Dependency Inversion).
 */
public interface SnapshotTrigger {

    /** No-op trigger for unit tests that do not wire persistence. */
    SnapshotTrigger NOOP = new SnapshotTrigger() {
        @Override
        public void save() {}

        @Override
        public boolean bgsave() {
            return true;
        }
    };

    /** Performs a synchronous blocking snapshot save. */
    void save();

    /**
     * Triggers an asynchronous background snapshot save.
     *
     * @return {@code true} if started, {@code false} if a background save is already in progress
     */
    boolean bgsave();

    /** Encodes a live in-memory snapshot for replication {@code PSYNC} full resync. */
    default byte[] encodeSnapshot() {
        return new byte[0];
    }
}
