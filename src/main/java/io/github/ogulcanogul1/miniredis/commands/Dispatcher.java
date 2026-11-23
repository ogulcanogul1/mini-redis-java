package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.EvictionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the command for an argv, enforces connection authentication / protected mode, checks
 * arity, and runs the handler. Unexpected {@link RuntimeException}s are caught here, logged at
 * ERROR (without user data), and translated to a generic error reply (K12).
 */
public final class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final int DEFAULT_EVICTION_SAMPLES = 5;

    private static final Reply NOAUTH_ERROR = new Reply.ErrorReply("NOAUTH Authentication required.");
    private static final Reply WRONGPASS_ERROR =
            new Reply.ErrorReply("WRONGPASS invalid username-password pair or user is disabled.");
    private static final Reply NO_PASSWORD_SET_ERROR =
            new Reply.ErrorReply("ERR Client sent AUTH, but no password is set");
    private static final Reply PROTECTED_MODE_ERROR = new Reply.ErrorReply(
            "DENIED Redis is running in protected mode because protected mode is enabled and no password is set for the default user. In this mode connections are only accepted from the loopback interface.");

    private final CommandTable table;
    private final CommandContext ctx;
    private final WriteListener writeListener;
    private final long maxMemoryBytes;
    private final EvictionPolicy evictionPolicy;
    private final byte[] requirePass;
    private final boolean protectedMode;

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
        this(table, ctx, writeListener, maxMemoryBytes, evictionPolicy, null, false);
    }

    public Dispatcher(
            CommandTable table,
            CommandContext ctx,
            WriteListener writeListener,
            long maxMemoryBytes,
            EvictionPolicy evictionPolicy,
            String requirePass,
            boolean protectedMode) {
        this.table = Objects.requireNonNull(table, "table");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.writeListener = Objects.requireNonNull(writeListener, "writeListener");
        if (maxMemoryBytes < 0) {
            throw new IllegalArgumentException("maxMemoryBytes must be >= 0, got " + maxMemoryBytes);
        }
        this.maxMemoryBytes = maxMemoryBytes;
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        this.requirePass = requirePass != null ? requirePass.getBytes(StandardCharsets.UTF_8) : null;
        this.protectedMode = protectedMode;
    }

    /** Creates the initial authentication/protected-mode state for a new client connection. */
    public SessionAuthState newSessionState(boolean loopback) {
        boolean blocked = protectedMode && requirePass == null && !loopback;
        boolean authenticated = requirePass == null;
        return new SessionAuthState(authenticated, blocked);
    }

    /** Runs {@code argv} with an unrestricted in-process session state. */
    public Reply dispatch(List<Bytes> argv) {
        return dispatch(argv, SessionAuthState.unrestricted());
    }

    /**
     * Runs {@code argv} for a client session with {@code authState}.
     *
     * @throws IllegalArgumentException if {@code argv} is empty
     */
    public Reply dispatch(List<Bytes> argv, SessionAuthState authState) {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(authState, "authState");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        Optional<CommandSpec> found = table.lookup(argv.getFirst());
        if (found.isEmpty()) {
            if (authState.isProtectedModeBlocked()) {
                return recordError(PROTECTED_MODE_ERROR);
            }
            if (!authState.isAuthenticated()) {
                return recordError(NOAUTH_ERROR);
            }
            return recordError(Errors.unknownCommand(argv));
        }
        CommandSpec spec = found.get();
        String cmdName = spec.name();
        if (authState.isProtectedModeBlocked() && !"quit".equals(cmdName)) {
            return recordError(PROTECTED_MODE_ERROR);
        }
        if ("auth".equals(cmdName)) {
            ctx.stats().recordCommand();
            if (!spec.acceptsArgCount(argv.size())) {
                return recordError(Errors.wrongArity(cmdName));
            }
            if (requirePass == null) {
                return recordError(NO_PASSWORD_SET_ERROR);
            }
            if (MessageDigest.isEqual(argv.get(1).toByteArray(), requirePass)) {
                authState.markAuthenticated();
                return Reply.OK;
            }
            return recordError(WRONGPASS_ERROR);
        }
        if (!authState.isAuthenticated() && !"quit".equals(cmdName)) {
            return recordError(NOAUTH_ERROR);
        }
        if (!spec.acceptsArgCount(argv.size())) {
            return recordError(Errors.wrongArity(cmdName));
        }
        ctx.stats().recordCommand();
        if (maxMemoryBytes > 0 && spec.flags().contains(CommandSpec.Flag.WRITE) && !freesMemory(cmdName)) {
            ctx.keyspace().evictIfNeeded(maxMemoryBytes, evictionPolicy, DEFAULT_EVICTION_SAMPLES);
            if (ctx.keyspace().usedMemoryBytes() > maxMemoryBytes) {
                return recordError(Errors.OOM);
            }
        }
        try {
            Reply reply = spec.handler().execute(ctx, argv);
            if (reply instanceof Reply.ErrorReply) {
                ctx.stats().recordErrorReply();
            } else if (spec.flags().contains(CommandSpec.Flag.WRITE)) {
                ctx.stats().recordWriteForRdb();
                writeListener.onWrite(spec, argv, reply);
                if (maxMemoryBytes > 0 && evictionPolicy != EvictionPolicy.NOEVICTION) {
                    ctx.keyspace().evictIfNeeded(maxMemoryBytes, evictionPolicy, DEFAULT_EVICTION_SAMPLES);
                }
            }
            return reply;
        } catch (RuntimeException e) {
            log.error("unexpected error while executing command '{}'", cmdName, e);
            return recordError(Errors.INTERNAL_ERROR);
        }
    }

    private Reply recordError(Reply reply) {
        ctx.stats().recordErrorReply();
        return reply;
    }

    private static boolean freesMemory(String name) {
        return "del".equals(name) || "unlink".equals(name) || "flushdb".equals(name);
    }
}
