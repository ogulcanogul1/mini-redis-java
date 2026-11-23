package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.KeyspaceResult;
import java.util.List;

/** Set commands: {@code SADD}, {@code SREM}, {@code SISMEMBER}, {@code SMEMBERS}. */
final class SetCommands {

    private SetCommands() {}

    static Reply sadd(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().setAdd(argv.get(1), argv.subList(2, argv.size()))) {
            case KeyspaceResult.Ok(var added) -> Reply.integer(added);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply srem(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().setRem(argv.get(1), argv.subList(2, argv.size()))) {
            case KeyspaceResult.Ok(var removed) -> Reply.integer(removed);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply sismember(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().setIsMember(argv.get(1), argv.get(2))) {
            case KeyspaceResult.Ok(var present) -> Reply.integer(present ? 1 : 0);
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }

    static Reply smembers(CommandContext ctx, List<Bytes> argv) {
        return switch (ctx.keyspace().setMembers(argv.get(1))) {
            case KeyspaceResult.Ok(var members) ->
                Reply.array(members.stream().map(Reply::bulk).<Reply>map(r -> r).toList());
            case KeyspaceResult.WrongType() -> Errors.WRONGTYPE;
        };
    }
}
