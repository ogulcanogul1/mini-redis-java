package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;

/**
 * Conservative JVM heap memory estimator for {@link Keyspace} entries (K6, PLAN §3 Faz 6.2). Uses
 * 64-bit compressed-oops layout approximations (16-byte object header, 8-byte alignment).
 */
final class MemoryEstimator {

    private static final int ENTRY_OVERHEAD = 32;
    private static final int BYTES_WRAPPER_OVERHEAD = 40;
    private static final int OBJECT_WRAPPER_OVERHEAD = 24;

    private MemoryEstimator() {}

    static long estimateEntry(Bytes key, RedisObject obj) {
        return ENTRY_OVERHEAD + estimateBytes(key) + estimateObject(obj);
    }

    static long estimateBytes(Bytes b) {
        return BYTES_WRAPPER_OVERHEAD + align8(b.length());
    }

    static long estimateObject(RedisObject obj) {
        return switch (obj) {
            case RedisObject.StringObj s -> OBJECT_WRAPPER_OVERHEAD + estimateBytes(s.value());
            case RedisObject.ListObj l -> {
                long total = OBJECT_WRAPPER_OVERHEAD + 48L + (long) l.deque().size() * 8L;
                for (Bytes v : l.deque()) {
                    total += estimateBytes(v);
                }
                yield total;
            }
            case RedisObject.HashObj h -> {
                long total = OBJECT_WRAPPER_OVERHEAD + 48L;
                for (Dict.Entry<Bytes, Bytes> e : h.map()) {
                    total += 32L + estimateBytes(e.key()) + estimateBytes(e.value());
                }
                yield total;
            }
            case RedisObject.SetObj s -> {
                long total = OBJECT_WRAPPER_OVERHEAD + 48L;
                for (Dict.Entry<Bytes, Boolean> e : s.members()) {
                    total += 32L + estimateBytes(e.key());
                }
                yield total;
            }
            case RedisObject.ZSetObj z -> {
                long total = OBJECT_WRAPPER_OVERHEAD + 96L;
                for (Dict.Entry<Bytes, Double> e : z.scores()) {
                    total += 64L + estimateBytes(e.key());
                }
                yield total;
            }
        };
    }

    private static long align8(int bytes) {
        return ((bytes + 7L) / 8L) * 8L;
    }
}
