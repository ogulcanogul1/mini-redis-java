package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.KeyspaceResult;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.List;
import java.util.OptionalLong;

/** List commands: {@code LPUSH}, {@code RPUSH}, {@code LPOP}, {@code RPOP}, {@code LRANGE}, {@code LLEN}. */
final class ListCommands {

    private ListCommands() {}

    static Reply lpush(CommandContext ctx, List<Bytes> argv) {
        return push(ctx, argv, true);
    }

    static Reply rpush(CommandContext ctx, List<Bytes> argv) {
        return push(ctx, argv, false);
    }

    private static Reply push(CommandContext ctx, List<Bytes> argv, boolean left) {
        return switch (ctx.keyspace().listPush(argv.get(1), argv.subList(2, argv.size()), left)) {
            case KeyspaceResult.Ok(var len) -> Reply.integer(len);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply lpop(CommandContext ctx, List<Bytes> argv) {
        return pop(ctx, argv, true);
    }

    static Reply rpop(CommandContext ctx, List<Bytes> argv) {
        return pop(ctx, argv, false);
    }

    private static Reply pop(CommandContext ctx, List<Bytes> argv, boolean left) {
        return switch (ctx.keyspace().listPop(argv.get(1), left)) {
            case KeyspaceResult.Ok(var opt) -> opt.map(Reply::bulk).orElse(Reply.NIL);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply lrange(CommandContext ctx, List<Bytes> argv) {
        OptionalLong start = parseLong(argv.get(2));
        OptionalLong stop = parseLong(argv.get(3));
        if (start.isEmpty() || stop.isEmpty()) {
            return Errors.NOT_INTEGER;
        }
        return switch (ctx.keyspace().listRange(argv.get(1), start.getAsLong(), stop.getAsLong())) {
            case KeyspaceResult.Ok(var items) ->
                Reply.array(items.stream().map(Reply::bulk).<Reply>map(r -> r).toList());
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply llen(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().listLen(argv.get(1))) {
            case KeyspaceResult.Ok(var len) -> Reply.integer(len);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    private static OptionalLong parseLong(Bytes raw) {
        byte[] data = raw.toByteArray();
        return Numbers.parseLong(data, 0, data.length);
    }
}
