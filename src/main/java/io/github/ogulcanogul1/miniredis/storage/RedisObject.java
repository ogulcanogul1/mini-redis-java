package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import io.github.ogulcanogul1.miniredis.ds.SkipList;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Polymorphic value wrapper stored in a {@link Keyspace} (K9). Uses a sealed hierarchy so callers
 * dispatch with exhaustive {@code switch} pattern matching.
 */
abstract sealed class RedisObject
        permits RedisObject.StringObj,
                RedisObject.ListObj,
                RedisObject.HashObj,
                RedisObject.SetObj,
                RedisObject.ZSetObj {

    enum Type {
        STRING,
        LIST,
        HASH,
        SET,
        ZSET
    }

    private volatile long lru;

    private RedisObject(long nowMillis) {
        this.lru = nowMillis;
    }

    static RedisObject string(Bytes value, long nowMillis) {
        return new StringObj(value, nowMillis);
    }

    static ListObj list(long nowMillis) {
        return new ListObj(nowMillis);
    }

    static HashObj hash(long nowMillis) {
        return new HashObj(nowMillis);
    }

    static SetObj set(long nowMillis) {
        return new SetObj(nowMillis);
    }

    static ZSetObj zset(long nowMillis) {
        return new ZSetObj(nowMillis);
    }

    abstract Type type();

    Bytes asString() {
        if (this instanceof StringObj s) {
            return s.value();
        }
        throw new IllegalStateException("expected STRING object, got " + type());
    }

    long lru() {
        return lru;
    }

    /** Updates the approximate LRU timestamp; benign race under read locks (K6). */
    void touch(long nowMillis) {
        this.lru = nowMillis;
    }

    static final class StringObj extends RedisObject {
        private final Bytes value;

        private StringObj(Bytes value, long nowMillis) {
            super(nowMillis);
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        Type type() {
            return Type.STRING;
        }

        Bytes value() {
            return value;
        }
    }

    static final class ListObj extends RedisObject {
        private final ArrayDeque<Bytes> deque = new ArrayDeque<>();

        private ListObj(long nowMillis) {
            super(nowMillis);
        }

        @Override
        Type type() {
            return Type.LIST;
        }

        ArrayDeque<Bytes> deque() {
            return deque;
        }
    }

    static final class HashObj extends RedisObject {
        private final Dict<Bytes, Bytes> map = new Dict<>();

        private HashObj(long nowMillis) {
            super(nowMillis);
        }

        @Override
        Type type() {
            return Type.HASH;
        }

        Dict<Bytes, Bytes> map() {
            return map;
        }
    }

    static final class SetObj extends RedisObject {
        private final Dict<Bytes, Boolean> members = new Dict<>();

        private SetObj(long nowMillis) {
            super(nowMillis);
        }

        @Override
        Type type() {
            return Type.SET;
        }

        Dict<Bytes, Boolean> members() {
            return members;
        }
    }

    static final class ZSetObj extends RedisObject {
        private final Dict<Bytes, Double> scores = new Dict<>();
        private final SkipList skipList = new SkipList();

        private ZSetObj(long nowMillis) {
            super(nowMillis);
        }

        @Override
        Type type() {
            return Type.ZSET;
        }

        Dict<Bytes, Double> scores() {
            return scores;
        }

        SkipList skipList() {
            return skipList;
        }
    }
}
