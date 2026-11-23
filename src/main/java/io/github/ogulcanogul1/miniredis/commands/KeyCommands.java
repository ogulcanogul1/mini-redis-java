package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Glob;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Generic key commands: {@code DEL}, {@code UNLINK}, {@code EXISTS}, {@code KEYS}, {@code DBSIZE},
 * {@code FLUSHDB}, {@code RENAME}, {@code EXPIRE}, {@code PEXPIRE}, {@code PEXPIREAT}, {@code TTL},
 * {@code PTTL}, {@code PERSIST}.
 */
final class KeyCommands {

    private KeyCommands() {}

    /** {@code DEL key [key ...]}: the number of keys deleted atomically. A repeated key counts once. */
    static Reply del(CommandContext ctx, List<Bytes> argv) {
        return Reply.integer(ctx.keyspace().deleteAll(argv.subList(1, argv.size())));
    }

    /** {@code EXISTS key [key ...]}: how many of the given keys exist atomically. Repeated keys count each time. */
    static Reply exists(CommandContext ctx, List<Bytes> argv) {
        return Reply.integer(ctx.keyspace().existsAll(argv.subList(1, argv.size())));
    }

    /** {@code RENAME source target}: atomically renames {@code source} to {@code target}. */
    static Reply rename(CommandContext ctx, List<Bytes> argv) {
        return ctx.keyspace().rename(argv.get(1), argv.get(2)) ? Reply.OK : Errors.NO_SUCH_KEY;
    }

    /** {@code KEYS pattern}: every key that matches the glob pattern, in no defined order. */
    static Reply keys(CommandContext ctx, List<Bytes> argv) {
        byte[] pattern = argv.get(1).toByteArray();
        List<Reply> matches = new ArrayList<>();
        ctx.keyspace().forEachKey(key -> {
            if (Glob.matches(pattern, key.toByteArray())) {
                matches.add(Reply.bulk(key));
            }
        });
        return Reply.array(matches);
    }

    /** {@code DBSIZE}: the number of keys. */
    static Reply dbsize(CommandContext ctx, List<Bytes> argv) {
        return Reply.integer(ctx.keyspace().size());
    }

    /** {@code FLUSHDB}: deletes every key. Any argument is a syntax error (no ASYNC/SYNC). */
    static Reply flushdb(CommandContext ctx, List<Bytes> argv) {
        if (argv.size() > 1) {
            return Errors.SYNTAX;
        }
        ctx.keyspace().flush();
        return Reply.OK;
    }

    /** {@code EXPIRE key seconds}: sets a relative TTL in seconds. */
    static Reply expire(CommandContext ctx, List<Bytes> argv) {
        OptionalLong parsed = parseLong(argv.get(2));
        if (parsed.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        long seconds = parsed.getAsLong();
        long ttlMillis;
        if (seconds <= 0) {
            ttlMillis = seconds;
        } else {
            try {
                ttlMillis = Math.multiplyExact(seconds, 1000L);
            } catch (ArithmeticException e) {
                return Errors.invalidExpireTime("expire");
            }
        }
        try {
            return Reply.integer(ctx.keyspace().expire(argv.get(1), ttlMillis) ? 1 : 0);
        } catch (IllegalArgumentException e) {
            return Errors.invalidExpireTime("expire");
        }
    }

    /** {@code PEXPIRE key milliseconds}: sets a relative TTL in milliseconds. */
    static Reply pexpire(CommandContext ctx, List<Bytes> argv) {
        OptionalLong parsed = parseLong(argv.get(2));
        if (parsed.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        try {
            return Reply.integer(ctx.keyspace().expire(argv.get(1), parsed.getAsLong()) ? 1 : 0);
        } catch (IllegalArgumentException e) {
            return Errors.invalidExpireTime("pexpire");
        }
    }

    /** {@code PEXPIREAT key unix-time-milliseconds}: sets an absolute expiration timestamp in ms. */
    static Reply pexpireAt(CommandContext ctx, List<Bytes> argv) {
        OptionalLong parsed = parseLong(argv.get(2));
        if (parsed.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        return Reply.integer(ctx.keyspace().expireAt(argv.get(1), parsed.getAsLong()) ? 1 : 0);
    }

    /** {@code TTL key}: remaining time-to-live in seconds ({@code -2} absent, {@code -1} persistent). */
    static Reply ttl(CommandContext ctx, List<Bytes> argv) {
        long pttl = ctx.keyspace().pttl(argv.get(1));
        if (pttl < 0) {
            return Reply.integer(pttl);
        }
        return Reply.integer((pttl + 500) / 1000);
    }

    /** {@code PTTL key}: remaining time-to-live in milliseconds ({@code -2} absent, {@code -1} persistent). */
    static Reply pttl(CommandContext ctx, List<Bytes> argv) {
        return Reply.integer(ctx.keyspace().pttl(argv.get(1)));
    }

    /** {@code PERSIST key}: removes an existing TTL, returning {@code 1} if removed. */
    static Reply persist(CommandContext ctx, List<Bytes> argv) {
        return Reply.integer(ctx.keyspace().persist(argv.get(1)) ? 1 : 0);
    }

    private static OptionalLong parseLong(Bytes raw) {
        byte[] data = raw.toByteArray();
        return Numbers.parseLong(data, 0, data.length);
    }
}
