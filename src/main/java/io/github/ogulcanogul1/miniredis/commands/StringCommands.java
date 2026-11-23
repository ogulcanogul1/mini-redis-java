package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;

/** String commands: {@code GET}, {@code SET}, etc. */
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
}
