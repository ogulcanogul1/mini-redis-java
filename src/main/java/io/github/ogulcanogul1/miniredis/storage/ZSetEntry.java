package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.Objects;

/** Immutable member-score pair returned by sorted-set queries (K3). */
public record ZSetEntry(Bytes member, double score) {
    public ZSetEntry {
        Objects.requireNonNull(member, "member");
    }
}
