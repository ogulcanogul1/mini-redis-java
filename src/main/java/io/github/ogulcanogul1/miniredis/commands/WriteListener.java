package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;

/**
 * Observer notified after a {@link CommandSpec.Flag#WRITE} command finishes without an error reply
 * (used by persistence / AOF in Faz 8).
 */
@FunctionalInterface
public interface WriteListener {

    void onWrite(CommandSpec spec, List<Bytes> argv, Reply reply);
}
