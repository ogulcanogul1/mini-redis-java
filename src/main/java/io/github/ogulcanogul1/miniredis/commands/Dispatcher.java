package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.EvictionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the command for an argv, checks its arity and runs it. Unexpected {@link RuntimeException}s
 * from a handler are caught here, logged at ERROR (without user data), and translated to a generic
 * error reply (K12).
 */
public final class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final int DEFAULT_EVICTION_SAMPLES = 5;

    private final CommandTable table;
    private final CommandContext ctx;
    private final WriteListener writeListener;
    private final long maxMemoryBytes;
    private final EvictionPolicy evictionPolicy;

    public Dispatcher(CommandTable table, CommandContext ctx) {
        this(table, ctx, (spec, argv, reply) -> {});
    }

    public Dispatcher(CommandTable table, CommandContext ctx, WriteListener writeListener) {
        this(table, ctx, writeListener, 0L, EvictionPolicy.NOEVICTION);
    }

    public Dispatcher(
            CommandTable table,
            CommandContext ctx,
            WriteListener writeListener,
            long maxMemoryBytes,
            EvictionPolicy evictionPolicy) {
        this.table = Objects.requireNonNull(table, "table");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.writeListener = Objects.requireNonNull(writeListener, "writeListener");
        if (maxMemoryBytes < 0) {
            throw new IllegalArgumentException("maxMemoryBytes must be >= 0, got " + maxMemoryBytes);
        }
        this.maxMemoryBytes = maxMemoryBytes;
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy");
    }

    /**
     * Runs {@code argv}.
     *
     * @param argv the command name followed by its arguments
     * @return the command's reply, or an error reply for an unknown command, a wrong number of
     *     arguments, or an unexpected internal failure
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
        ctx.stats().recordCommand();
        if (maxMemoryBytes > 0 && spec.flags().contains(CommandSpec.Flag.WRITE) && !freesMemory(spec.name())) {
            ctx.keyspace().evictIfNeeded(maxMemoryBytes, evictionPolicy, DEFAULT_EVICTION_SAMPLES);
            if (ctx.keyspace().usedMemoryBytes() > maxMemoryBytes) {
                return Errors.OOM;
            }
        }
        try {
            Reply reply = spec.handler().execute(ctx, argv);
            if (spec.flags().contains(CommandSpec.Flag.WRITE) && !(reply instanceof Reply.ErrorReply)) {
                writeListener.onWrite(spec, argv, reply);
                if (maxMemoryBytes > 0 && evictionPolicy != EvictionPolicy.NOEVICTION) {
                    ctx.keyspace().evictIfNeeded(maxMemoryBytes, evictionPolicy, DEFAULT_EVICTION_SAMPLES);
                }
            }
            return reply;
        } catch (RuntimeException e) {
            log.error("unexpected error while executing command '{}'", spec.name(), e);
            return Errors.INTERNAL_ERROR;
        }
    }

    private static boolean freesMemory(String name) {
        return name.equals("del")
                || name.equals("unlink")
                || name.equals("flushdb")
                || name.equals("hdel")
                || name.equals("srem")
                || name.equals("lpop")
                || name.equals("rpop");
    }
}
