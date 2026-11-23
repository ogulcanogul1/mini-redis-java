package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.KeyspaceResult;
import io.github.ogulcanogul1.miniredis.storage.ZSetEntry;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Sorted-set commands: {@code ZADD}, {@code ZSCORE}, {@code ZRANGE}, {@code ZRANGEBYSCORE}, {@code ZRANK}. */
final class ZSetCommands {

    private static final Bytes OPT_WITHSCORES = Bytes.ascii("withscores");

    private ZSetCommands() {}

    static Reply zadd(CommandContext ctx, List<Bytes> argv) {
        if ((argv.size() & 1) != 0) {
            return Errors.SYNTAX;
        }
        List<ZSetEntry> entries = new ArrayList<>((argv.size() - 2) / 2);
        for (int i = 2; i < argv.size(); i += 2) {
            OptionalDouble score = parseDouble(argv.get(i), false);
            if (score.isEmpty()) {
                return Errors.NOT_FLOAT;
            }
            entries.add(new ZSetEntry(argv.get(i + 1), score.getAsDouble()));
        }
        return switch (ctx.keyspace().zadd(argv.get(1), entries)) {
            case KeyspaceResult.Ok(var added) -> Reply.integer(added);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply zscore(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().zscore(argv.get(1), argv.get(2))) {
            case KeyspaceResult.Ok(var opt) ->
                opt.isPresent() ? Reply.bulk(Bytes.ascii(formatScore(opt.getAsDouble()))) : Reply.NIL;
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply zrange(CommandContext ctx, List<Bytes> argv) {
        if (argv.size() > 5) {
            return Errors.SYNTAX;
        }
        boolean withScores = false;
        if (argv.size() == 5) {
            if (!argv.get(4).toLowerAscii().equals(OPT_WITHSCORES)) {
                return Errors.SYNTAX;
            }
            withScores = true;
        }
        OptionalLong start = parseLong(argv.get(2));
        OptionalLong stop = parseLong(argv.get(3));
        if (start.isEmpty() || stop.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        return switch (ctx.keyspace().zrange(argv.get(1), start.getAsLong(), stop.getAsLong())) {
            case KeyspaceResult.Ok(var entries) -> formatEntries(entries, withScores);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply zrangeByScore(CommandContext ctx, List<Bytes> argv) {
        if (argv.size() > 5) {
            return Errors.SYNTAX;
        }
        boolean withScores = false;
        if (argv.size() == 5) {
            if (!argv.get(4).toLowerAscii().equals(OPT_WITHSCORES)) {
                return Errors.SYNTAX;
            }
            withScores = true;
        }
        OptionalDouble min = parseDouble(argv.get(2), true);
        OptionalDouble max = parseDouble(argv.get(3), true);
        if (min.isEmpty() || max.isEmpty()) {
            return Errors.MIN_MAX_NOT_FLOAT;
        }
        return switch (ctx.keyspace().zrangeByScore(argv.get(1), min.getAsDouble(), max.getAsDouble())) {
            case KeyspaceResult.Ok(var entries) -> formatEntries(entries, withScores);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply zrank(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().zrank(argv.get(1), argv.get(2))) {
            case KeyspaceResult.Ok(var opt) -> opt.isPresent() ? Reply.integer(opt.getAsLong()) : Reply.NIL;
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    private static Reply formatEntries(List<ZSetEntry> entries, boolean withScores) {
        List<Reply> out = new ArrayList<>(withScores ? entries.size() * 2 : entries.size());
        for (ZSetEntry e : entries) {
            out.add(Reply.bulk(e.member()));
            if (withScores) {
                out.add(Reply.bulk(Bytes.ascii(formatScore(e.score()))));
            }
        }
        return Reply.array(out);
    }

    private static OptionalDouble parseDouble(Bytes raw, boolean allowInf) {
        String s = new String(raw.toByteArray(), StandardCharsets.US_ASCII).trim();
        if (s.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (allowInf) {
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.equals("-inf")) {
                return OptionalDouble.of(Double.NEGATIVE_INFINITY);
            }
            if (lower.equals("inf") || lower.equals("+inf")) {
                return OptionalDouble.of(Double.POSITIVE_INFINITY);
            }
        }
        try {
            double v = Double.parseDouble(s);
            return Double.isNaN(v) ? OptionalDouble.empty() : OptionalDouble.of(v);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static OptionalLong parseLong(Bytes raw) {
        byte[] data = raw.toByteArray();
        return Numbers.parseLong(data, 0, data.length);
    }

    private static String formatScore(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) <= 9e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
