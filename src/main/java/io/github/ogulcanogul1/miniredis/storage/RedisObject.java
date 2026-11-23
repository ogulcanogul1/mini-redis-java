package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.Objects;

/**
 * Value wrapper stored in a {@link Keyspace} (K9). Wraps a {@link Type} tag, a volatile
 * millisecond access timestamp ({@code lru}, K6), and the immutable payload.
 */
final class RedisObject {

    enum Type {
        STRING
    }

    private final Type type;
    private volatile long lru;
    private final Bytes value;

    private RedisObject(Type type, long lru, Bytes value) {
        this.type = Objects.requireNonNull(type, "type");
        this.lru = lru;
        this.value = Objects.requireNonNull(value, "value");
    }

    static RedisObject string(Bytes value, long nowMillis) {
        return new RedisObject(Type.STRING, nowMillis, value);
    }

    Type type() {
        return type;
    }

    Bytes asString() {
        if (type != Type.STRING) {
            throw new IllegalStateException("expected STRING object, got " + type);
        }
        return value;
    }

    long lru() {
        return lru;
    }

    /** Updates the approximate LRU timestamp; benign race under read locks (K6). */
    void touch(long nowMillis) {
        this.lru = nowMillis;
    }
}
