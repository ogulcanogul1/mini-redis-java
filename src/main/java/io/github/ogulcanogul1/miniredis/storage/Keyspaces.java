package io.github.ogulcanogul1.miniredis.storage;

/** Creates {@link Keyspace} instances. The implementations themselves are package-private. */
public final class Keyspaces {

    private Keyspaces() {}

    /** Returns a new, empty, single-threaded in-memory keyspace. */
    public static Keyspace inMemory() {
        return new DictKeyspace();
    }
}
