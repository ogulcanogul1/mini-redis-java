package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Glob;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic key commands: {@code DEL}, {@code EXISTS}, {@code KEYS}, {@code DBSIZE}, {@code FLUSHDB},
 * {@code RENAME}.
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
}
