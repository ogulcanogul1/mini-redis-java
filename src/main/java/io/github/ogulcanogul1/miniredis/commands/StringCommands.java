package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.IncrResult;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.List;
import java.util.OptionalLong;

/** String commands: {@code SET}, {@code GET}, {@code INCR}, {@code DECR}, {@code INCRBY}, {@code DECRBY}, {@code MGET}, {@code MSET}. */
final class StringCommands {

    private StringCommands() {}

    /** {@code SET key value}: always {@code +OK}. Options (EX, PX, NX, XX) come in Faz 6. */
    static Reply set(CommandContext ctx, List<Bytes> argv) {
        ctx.keyspace().set(argv.get(1), argv.get(2));
        return Reply.OK;
    }

    /** {@code GET key}: the value as a bulk string, or nil. */
    static Reply get(CommandContext ctx, List<Bytes> argv) {
        return ctx.keyspace().get(argv.get(1)).map(Reply::bulk).orElse(Reply.NIL);
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
