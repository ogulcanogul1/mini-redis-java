package io.github.ogulcanogul1.miniredis.persistence;

import io.github.ogulcanogul1.miniredis.commands.CommandSpec;
import io.github.ogulcanogul1.miniredis.commands.Reply;
import io.github.ogulcanogul1.miniredis.commands.WriteListener;
import io.github.ogulcanogul1.miniredis.concurrency.NamedThreadFactory;
import io.github.ogulcanogul1.miniredis.concurrency.PeriodicTask;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Clock;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends successful write commands to an {@link AofChannel} in RESP2 format, translating relative
 * TTLs into absolute {@code PEXPIREAT} commands (K8, PLAN §3 Faz 8.1) and managing fsync policies.
 */
public final class AofAppender implements WriteListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AofAppender.class);

    private static final Bytes CMD_SET = Bytes.ascii("SET");
    private static final Bytes CMD_DEL = Bytes.ascii("DEL");
    private static final Bytes CMD_PEXPIREAT = Bytes.ascii("PEXPIREAT");
    private static final Bytes OPT_EX = Bytes.ascii("ex");
    private static final Bytes OPT_PX = Bytes.ascii("px");

    private final AofChannel channel;
    private final AofFsyncPolicy policy;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private boolean dirty;
    private boolean closed;

    public AofAppender(AofChannel channel, AofFsyncPolicy policy, Clock clock) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = policy == AofFsyncPolicy.EVERYSEC
                ? Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("aof-fsync"))
                : null;
    }

    /** Opens or creates an AOF file at {@code path} positioned at the end for appending. */
    public static AofAppender open(Path path, AofFsyncPolicy policy, Clock clock) throws IOException {
        return new AofAppender(new FileAofChannel(path), policy, clock);
    }

    /** Starts the background {@code everysec} fsync task if configured. */
    public void start() {
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(
                    PeriodicTask.wrap(this::fsyncIfDirty, e -> log.error("periodic task 'aof-fsync' failed", e)),
                    1,
                    1,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    public void onWrite(CommandSpec spec, List<Bytes> argv, Reply reply) {
        if (!spec.flags().contains(CommandSpec.Flag.WRITE) || reply instanceof Reply.ErrorReply) {
            return;
        }
        byte[] payload = encodeForAof(spec.name(), argv, reply);
        if (payload == null) {
            return;
        }
        lock.lock();
        try {
            if (closed) {
                return;
            }
            channel.writeAll(ByteBuffer.wrap(payload));
            dirty = true;
            if (policy == AofFsyncPolicy.ALWAYS) {
                channel.force(false);
                dirty = false;
            }
        } catch (IOException e) {
            log.error("AOF write/fsync failed for command '{}'", spec.name(), e);
            throw new AofException("AOF write failed", e);
        } finally {
            lock.unlock();
        }
    }

    /** Flushes pending OS buffers to disk if any writes occurred since the last fsync. */
    public void fsyncIfDirty() {
        lock.lock();
        try {
            if (closed || !dirty) {
                return;
            }
            channel.force(false);
            dirty = false;
        } catch (IOException e) {
            log.error("AOF background fsync failed", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (dirty && policy != AofFsyncPolicy.NO) {
                channel.force(false);
                dirty = false;
            }
            channel.close();
        } finally {
            lock.unlock();
        }
    }

    private byte[] encodeForAof(String name, List<Bytes> argv, Reply reply) {
        if (reply instanceof Reply.NullBulk) {
            return null;
        }
        if (reply instanceof Reply.IntegerReply intReply
                && intReply.value() == 0
                && (name.equals("expire")
                        || name.equals("pexpire")
                        || name.equals("pexpireat")
                        || name.equals("persist")
                        || name.equals("del")
                        || name.equals("unlink"))) {
            return null;
        }
        if (name.equals("set")) {
            return encodeSet(argv);
        }
        if (name.equals("expire")) {
            long seconds = parseLong(argv.get(2));
            if (seconds <= 0) {
                return AofCodec.encodeCommand(List.of(CMD_DEL, argv.get(1)));
            }
            long expireAt = Math.addExact(clock.millis(), Math.multiplyExact(seconds, 1000L));
            return AofCodec.encodeCommand(List.of(CMD_PEXPIREAT, argv.get(1), Bytes.ascii(Long.toString(expireAt))));
        }
        if (name.equals("pexpire")) {
            long ttlMillis = parseLong(argv.get(2));
            if (ttlMillis <= 0) {
                return AofCodec.encodeCommand(List.of(CMD_DEL, argv.get(1)));
            }
            long expireAt = Math.addExact(clock.millis(), ttlMillis);
            return AofCodec.encodeCommand(List.of(CMD_PEXPIREAT, argv.get(1), Bytes.ascii(Long.toString(expireAt))));
        }
        return AofCodec.encodeCommand(argv);
    }

    private byte[] encodeSet(List<Bytes> argv) {
        Long ttlMillis = null;
        for (int i = 3; i < argv.size(); i++) {
            Bytes opt = argv.get(i).toLowerAscii();
            if (opt.equals(OPT_EX)) {
                ttlMillis = Math.multiplyExact(parseLong(argv.get(++i)), 1000L);
            } else if (opt.equals(OPT_PX)) {
                ttlMillis = parseLong(argv.get(++i));
            }
        }
        byte[] setBytes = AofCodec.encodeCommand(List.of(CMD_SET, argv.get(1), argv.get(2)));
        if (ttlMillis == null) {
            return setBytes;
        }
        long expireAt = Math.addExact(clock.millis(), ttlMillis);
        byte[] expireBytes =
                AofCodec.encodeCommand(List.of(CMD_PEXPIREAT, argv.get(1), Bytes.ascii(Long.toString(expireAt))));
        byte[] combined = new byte[setBytes.length + expireBytes.length];
        System.arraycopy(setBytes, 0, combined, 0, setBytes.length);
        System.arraycopy(expireBytes, 0, combined, setBytes.length, expireBytes.length);
        return combined;
    }

    private static long parseLong(Bytes raw) {
        byte[] data = raw.toByteArray();
        return Numbers.parseLong(data, 0, data.length).orElseThrow();
    }
}
