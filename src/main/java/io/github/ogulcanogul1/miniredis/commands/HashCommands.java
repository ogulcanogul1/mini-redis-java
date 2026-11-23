package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.KeyspaceResult;
import java.util.List;

/** Hash commands: {@code HSET}, {@code HGET}, {@code HDEL}, {@code HGETALL}. */
final class HashCommands {

    private HashCommands() {}

    static Reply hset(CommandContext ctx, List<Bytes> argv) {
        if ((argv.size() & 1) != 0) {
            return Errors.wrongArity("hset");
        }
        return switch (ctx.keyspace().hashSet(argv.get(1), argv.subList(2, argv.size()))) {
            case KeyspaceResult.Ok(var added) -> Reply.integer(added);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply hget(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().hashGet(argv.get(1), argv.get(2))) {
            case KeyspaceResult.Ok(var opt) -> opt.map(Reply::bulk).orElse(Reply.NIL);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply hdel(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().hashDel(argv.get(1), argv.subList(2, argv.size()))) {
            case KeyspaceResult.Ok(var removed) -> Reply.integer(removed);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply hgetall(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().hashGetAll(argv.get(1))) {
            case KeyspaceResult.Ok(var pairs) ->
                Reply.array(pairs.stream().map(Reply::bulk).<Reply>map(r -> r).toList());
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }
}
