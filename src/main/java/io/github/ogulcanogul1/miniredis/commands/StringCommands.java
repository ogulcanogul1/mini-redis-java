package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.IncrResult;
import io.github.ogulcanogul1.miniredis.storage.KeyspaceResult;
import io.github.ogulcanogul1.miniredis.storage.SetCondition;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.List;
import java.util.OptionalLong;

/** String commands: {@code SET}, {@code GET}, {@code INCR}, {@code DECR}, {@code INCRBY}, {@code DECRBY}, {@code MGET}, {@code MSET}. */
final class StringCommands {

    private static final Bytes OPT_EX = Bytes.ascii("ex");
    private static final Bytes OPT_PX = Bytes.ascii("px");
    private static final Bytes OPT_NX = Bytes.ascii("nx");
    private static final Bytes OPT_XX = Bytes.ascii("xx");

    private StringCommands() {}

    /** {@code SET key value [NX | XX] [EX seconds | PX milliseconds]}. */
    static Reply set(CommandContext ctx, List<Bytes> argv) {
        SetCondition condition = null;
        Long ttlMillis = null;
        for (int i = 3; i < argv.size(); i++) {
            Bytes opt = argv.get(i).toLowerAscii();
            if (opt.equals(OPT_NX)) {
                if (condition == SetCondition.IF_PRESENT) {
                    return Errors.SYNTAX;
                }
                condition = SetCondition.IF_ABSENT;
            } else if (opt.equals(OPT_XX)) {
                if (condition == SetCondition.IF_ABSENT) {
                    return Errors.SYNTAX;
                }
                condition = SetCondition.IF_PRESENT;
            } else if (opt.equals(OPT_EX) || opt.equals(OPT_PX)) {
                if (ttlMillis != null || i + 1 >= argv.size()) {
                    return Errors.SYNTAX;
                }
                OptionalLong parsed = parseDelta(argv.get(++i));
                if (parsed.isEmpty()) {
                    return Errors.NOT_INTEGER;
                }
                long raw = parsed.getAsLong();
                if (raw <= 0) {
                    return Errors.invalidExpireTime("set");
                }
                if (opt.equals(OPT_EX)) {
                    try {
                        ttlMillis = Math.multiplyExact(raw, 1000L);
                    } catch (ArithmeticException e) {
                        return Errors.invalidExpireTime("set");
                    }
                } else {
                    ttlMillis = raw;
                }
            } else {
                return Errors.SYNTAX;
            }
        }
        SetCondition effective = condition == null ? SetCondition.ALWAYS : condition;
        try {
            boolean written = ctx.keyspace().set(argv.get(1), argv.get(2), effective, ttlMillis);
            return written ? Reply.OK : Reply.NIL;
        } catch (IllegalArgumentException e) {
            return Errors.invalidExpireTime("set");
        }
    }

    /** {@code GET key}: the value as a bulk string, or nil. */
    static Reply get(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().getString(argv.get(1))) {
            case KeyspaceResult.Ok(var opt) -> opt.map(Reply::bulk).orElse(Reply.NIL);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    /** {@code INCR key}: increments integer value by 1 (0 if absent). */
    static Reply incr(CommandContext ctx, List<Bytes> argv) {
        return applyDelta(ctx, argv.get(1), 1);
    }

    /** {@code DECR key}: decrements integer value by 1 (0 if absent). */
    static Reply decr(CommandContext ctx, List<Bytes> argv) {
        return applyDelta(ctx, argv.get(1), -1);
    }

    /** {@code INCRBY key increment}: adds {@code increment} atomically. */
    static Reply incrBy(CommandContext ctx, List<Bytes> argv) {
        OptionalLong delta = parseDelta(argv.get(2));
        if (delta.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        return applyDelta(ctx, argv.get(1), delta.getAsLong());
    }

    /** {@code DECRBY key decrement}: subtracts {@code decrement} atomically. */
    static Reply decrBy(CommandContext ctx, List<Bytes> argv) {
        OptionalLong delta = parseDelta(argv.get(2));
        if (delta.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        long raw = delta.getAsLong();
        if (raw == Long.MIN_VALUE) {
            return Errors.DECREMENT_OVERFLOW;
        }
        return applyDelta(ctx, argv.get(1), -raw);
    }

    /** {@code MGET key [key ...]}: array of values (bulk string or nil). */
    static Reply mget(CommandContext ctx, List<Bytes> argv) {
        List<Reply> replies = ctx.keyspace().mget(argv.subList(1, argv.size())).stream()
                .map(opt -> opt.map(Reply::bulk).orElse(Reply.NIL))
                .toList();
        return Reply.array(replies);
    }

    /** {@code MSET key value [key value ...]}: atomic multi-key write, requires key/value pairs. */
    static Reply mset(CommandContext ctx, List<Bytes> argv) {
        if ((argv.size() & 1) == 0) {
            return Errors.wrongArity("mset");
        }
        ctx.keyspace().mset(argv.subList(1, argv.size()));
        return Reply.OK;
    }

    private static OptionalLong parseDelta(Bytes raw) {
        byte[] data = raw.toByteArray();
        return Numbers.parseLong(data, 0, data.length);
    }

    private static Reply applyDelta(CommandContext ctx, Bytes key, long delta) {
        return switch (ctx.keyspace().incrBy(key, delta)) {
            case IncrResult.Ok ok -> Reply.integer(ok.value());
            case IncrResult.NotAnInteger ignored -> Errors.NOT_INTEGER;
            case IncrResult.Overflow ignored -> Errors.OVERFLOW;
        };
    }
}
