package io.github.ogulcanogul1.miniredis.commands;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;

public interface CommandHandler {

    Reply execute(CommandContext ctx, List<Bytes> argv);
}
