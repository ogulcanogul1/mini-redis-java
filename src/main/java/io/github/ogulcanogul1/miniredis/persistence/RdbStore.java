package io.github.ogulcanogul1.miniredis.persistence;

import io.github.ogulcanogul1.miniredis.concurrency.NamedThreadFactory;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.SnapshotEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves and loads {@link Keyspace} snapshots using atomic temp-file replacement (PLAN §3 Faz 8.2).
 */
public final class RdbStore {

    private static final Logger log = LoggerFactory.getLogger(RdbStore.class);

    private final AtomicBoolean bgSaveRunning = new AtomicBoolean(false);
    private final ThreadFactory bgThreadFactory = new NamedThreadFactory("rdb-bgsave");

    /** Writes a snapshot of {@code keyspace} to a temporary file, forces it, and atomically moves it to {@code target}. */
    public void save(Keyspace keyspace, Path target) throws IOException {
        Objects.requireNonNull(keyspace, "keyspace");
        Objects.requireNonNull(target, "target");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] payload = RdbCodec.encode(keyspace.snapshotEntries());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(
                tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(payload);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Triggers an asynchronous background snapshot using {@link Keyspace#snapshotEntries()} (K5).
     *
     * @return {@code true} if started, {@code false} if a background save is already in progress
     */
    public boolean bgsave(Keyspace keyspace, Path target) {
        Objects.requireNonNull(keyspace, "keyspace");
        Objects.requireNonNull(target, "target");
        if (!bgSaveRunning.compareAndSet(false, true)) {
            return false;
        }
        Thread t = bgThreadFactory.newThread(() -> {
            try {
                save(keyspace, target);
                log.info("Background saving terminated with success");
            } catch (IOException | RuntimeException e) {
                log.error("Background RDB save failed", e);
            } finally {
                bgSaveRunning.set(false);
            }
        });
        t.start();
        return true;
    }

    /** Returns whether a background save is currently running. */
    public boolean isBgSaveRunning() {
        return bgSaveRunning.get();
    }

    /** Loads {@code source} into {@code keyspace} if the file exists; returns loaded entry count. */
    public int load(Keyspace keyspace, Path source) throws IOException {
        Objects.requireNonNull(keyspace, "keyspace");
        Objects.requireNonNull(source, "source");
        if (!Files.exists(source)) {
            return 0;
        }
        byte[] data = Files.readAllBytes(source);
        List<SnapshotEntry> entries = RdbCodec.decode(data);
        keyspace.restoreSnapshot(entries);
        return keyspace.size();
    }
}
