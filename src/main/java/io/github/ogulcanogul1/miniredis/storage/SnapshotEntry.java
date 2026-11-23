package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.List;
import java.util.Objects;

/**
 * Immutable shallow-copy snapshot of one {@link Keyspace} entry (K5). {@link Bytes} payloads are
 * immutable and shared without copying underlying byte arrays; mutable collection shells are
 * copied under lock (K3).
 */
public record SnapshotEntry(Bytes key, long expireAtMillis, Value value) {

    public SnapshotEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }

    /** Polymorphic immutable value representation for RDB snapshots. */
    public sealed interface Value permits StringVal, ListVal, HashVal, SetVal, ZSetVal {}

    public record StringVal(Bytes value) implements Value {
        public StringVal {
            Objects.requireNonNull(value, "value");
        }
    }

    public record ListVal(List<Bytes> elements) implements Value {
        public ListVal {
            elements = List.copyOf(elements);
        }
    }

    public record HashVal(List<Bytes> fieldsAndValues) implements Value {
        public HashVal {
            fieldsAndValues = List.copyOf(fieldsAndValues);
        }
    }

    public record SetVal(List<Bytes> members) implements Value {
        public SetVal {
            members = List.copyOf(members);
        }
    }

    public record ZSetVal(List<ZSetEntry> entries) implements Value {
        public ZSetVal {
            entries = List.copyOf(entries);
        }
    }
}
