package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.util.Glob;
import java.util.ArrayList;
import java.util.List;

/** Generic key commands: {@code DEL}, {@code EXISTS}, {@code KEYS}, {@code DBSIZE}, {@code FLUSHDB}. */
final class KeyCommands {

    private KeyCommands() {}

    /** {@code DEL key [key ...]}: the number of keys deleted. A repeated key counts once. */
    static Reply del(CommandContext ctx, List<Bytes> argv) {
        Keyspace ks = ctx.keyspace();
        long deleted = 0;
        for (Bytes key : argv.subList(1, argv.size())) {
            if (ks.delete(key)) {
                deleted++; // the second "a" in DEL a a finds nothing, so it is not counted
            }
        }
        return Reply.integer(deleted);
    }

    /** {@code EXISTS key [key ...]}: how many of the given keys exist. A repeated key counts each time. */
    static Reply exists(CommandContext ctx, List<Bytes> argv) {
        Keyspace ks = ctx.keyspace();
        long count = 0;
        for (Bytes key : argv.subList(1, argv.size())) {
            if (ks.exists(key)) {
                count++;
            }
        }
        return Reply.integer(count);
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
