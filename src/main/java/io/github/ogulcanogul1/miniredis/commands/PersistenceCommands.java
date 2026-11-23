package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.io.UncheckedIOException;
import java.util.List;

/** Handlers for snapshot persistence commands: {@code SAVE} and {@code BGSAVE}. */
final class PersistenceCommands {

    private PersistenceCommands() {}

    static Reply save(CommandContext ctx, List<Bytes> argv) {
        try {
            ctx.snapshotTrigger().save();
            return Reply.OK;
        } catch (UncheckedIOException e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return new Reply.ErrorReply("ERR " + (msg != null ? msg : "snapshot save failed"));
        }
    }

    static Reply bgsave(CommandContext ctx, List<Bytes> argv) {
        if (ctx.snapshotTrigger().bgsave()) {
            return new Reply.SimpleString("Background saving started");
        }
        return new Reply.ErrorReply("ERR Background save already in progress");
    }
}
