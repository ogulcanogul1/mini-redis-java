package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import io.github.ogulcanogul1.miniredis.ds.SkipList;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Shared validation, arithmetic, and collection helpers used by {@link Keyspace} implementations. */
final class KeyspaceSupport {

    private KeyspaceSupport() {}

    static IncrResult computeIncrement(Bytes current, long delta) {
        long base;
        if (current == null) {
            base = 0;
        } else {
            byte[] data = current.toByteArray();
            OptionalLong parsed = Numbers.parseLong(data, 0, data.length);
            if (parsed.isEmpty()) {
                return new IncrResult.NotAnInteger();
            }
            base = parsed.getAsLong();
        }
        try {
            return new IncrResult.Ok(Math.addExact(base, delta));
        } catch (ArithmeticException e) {
            return new IncrResult.Overflow();
        }
    }

    static Bytes formatLong(long value) {
        return Bytes.ascii(Long.toString(value));
    }

    static boolean isExpired(Long expireAtMillis, long nowMillis) {
        return expireAtMillis != null && expireAtMillis <= nowMillis;
    }

    static long computeExpireAt(long nowMillis, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive, got " + ttlMillis);
        }
        try {
            return Math.addExact(nowMillis, ttlMillis);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("invalid expire time", e);
        }
    }

    static void validateNonNullKeys(List<Bytes> keys) {
        Objects.requireNonNull(keys, "keys");
        for (Bytes key : keys) {
            Objects.requireNonNull(key, "key");
        }
    }

    static void validateMsetPairs(List<Bytes> keysAndValues) {
        Objects.requireNonNull(keysAndValues, "keysAndValues");
        if ((keysAndValues.size() & 1) != 0) {
            throw new IllegalArgumentException("keysAndValues must have an even length");
        }
        for (int i = 0; i < keysAndValues.size(); i += 2) {
            Objects.requireNonNull(keysAndValues.get(i), "key");
            Objects.requireNonNull(keysAndValues.get(i + 1), "value");
        }
    }

    static KeyspaceResult<Optional<Bytes>> readString(RedisObject obj) {
        if (obj == null) {
            return KeyspaceResult.ok(Optional.empty());
        }
        if (obj instanceof RedisObject.StringObj s) {
            return KeyspaceResult.ok(Optional.of(s.value()));
        }
        return KeyspaceResult.wrongType();
    }

    static int pushList(RedisObject.ListObj listObj, List<Bytes> values, boolean left) {
        ArrayDeque<Bytes> deque = listObj.deque();
        for (Bytes v : values) {
            Objects.requireNonNull(v, "value");
            if (left) {
                deque.addFirst(v);
            } else {
                deque.addLast(v);
            }
        }
        return deque.size();
    }

    static Optional<Bytes> popList(RedisObject.ListObj listObj, boolean left) {
        ArrayDeque<Bytes> deque = listObj.deque();
        Bytes v = left ? deque.pollFirst() : deque.pollLast();
        return Optional.ofNullable(v);
    }

    static List<Bytes> rangeList(RedisObject.ListObj listObj, long start, long stop) {
        ArrayDeque<Bytes> deque = listObj.deque();
        int len = deque.size();
        if (len == 0) {
            return List.of();
        }
        long s = start < 0 ? len + start : start;
        long e = stop < 0 ? len + stop : stop;
        if (s < 0) {
            s = 0;
        }
        if (e >= len) {
            e = len - 1;
        }
        if (s > e) {
            return List.of();
        }
        List<Bytes> result = new ArrayList<>((int) (e - s + 1));
        int idx = 0;
        for (Bytes v : deque) {
            if (idx > e) {
                break;
            }
            if (idx >= s) {
                result.add(v);
            }
            idx++;
        }
        return List.copyOf(result);
    }

    static int setHash(RedisObject.HashObj hashObj, List<Bytes> fieldsAndValues) {
        Dict<Bytes, Bytes> map = hashObj.map();
        int added = 0;
        for (int i = 0; i < fieldsAndValues.size(); i += 2) {
            Bytes field = Objects.requireNonNull(fieldsAndValues.get(i), "field");
            Bytes value = Objects.requireNonNull(fieldsAndValues.get(i + 1), "value");
            if (map.put(field, value) == null) {
                added++;
            }
        }
        return added;
    }

    static int delHash(RedisObject.HashObj hashObj, List<Bytes> fields) {
        Dict<Bytes, Bytes> map = hashObj.map();
        int removed = 0;
        for (Bytes field : fields) {
            Objects.requireNonNull(field, "field");
            if (map.remove(field) != null) {
                removed++;
            }
        }
        return removed;
    }

    static List<Bytes> getAllHash(RedisObject.HashObj hashObj) {
        Dict<Bytes, Bytes> map = hashObj.map();
        List<Bytes> out = new ArrayList<>(map.size() * 2);
        for (Dict.Entry<Bytes, Bytes> e : map) {
            out.add(e.key());
            out.add(e.value());
        }
        return List.copyOf(out);
    }

    static int addSet(RedisObject.SetObj setObj, List<Bytes> members) {
        Dict<Bytes, Boolean> dict = setObj.members();
        int added = 0;
        for (Bytes m : members) {
            Objects.requireNonNull(m, "member");
            if (dict.put(m, Boolean.TRUE) == null) {
                added++;
            }
        }
        return added;
    }

    static int remSet(RedisObject.SetObj setObj, List<Bytes> members) {
        Dict<Bytes, Boolean> dict = setObj.members();
        int removed = 0;
        for (Bytes m : members) {
            Objects.requireNonNull(m, "member");
            if (dict.remove(m) != null) {
                removed++;
            }
        }
        return removed;
    }

    static List<Bytes> allSetMembers(RedisObject.SetObj setObj) {
        Dict<Bytes, Boolean> dict = setObj.members();
        List<Bytes> out = new ArrayList<>(dict.size());
        for (Dict.Entry<Bytes, Boolean> e : dict) {
            out.add(e.key());
        }
        return List.copyOf(out);
    }

    static int addZSet(RedisObject.ZSetObj zsetObj, List<ZSetEntry> entries) {
        Dict<Bytes, Double> scores = zsetObj.scores();
        SkipList skipList = zsetObj.skipList();
        int added = 0;
        for (ZSetEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            Double prev = scores.get(entry.member());
            if (prev == null) {
                scores.put(entry.member(), entry.score());
                skipList.insert(entry.member(), entry.score());
                added++;
            } else if (Double.compare(prev, entry.score()) != 0) {
                skipList.delete(entry.member(), prev);
                scores.put(entry.member(), entry.score());
                skipList.insert(entry.member(), entry.score());
            }
        }
        return added;
    }

    static OptionalDouble scoreZSet(RedisObject.ZSetObj zsetObj, Bytes member) {
        Double s = zsetObj.scores().get(member);
        return s == null ? OptionalDouble.empty() : OptionalDouble.of(s);
    }

    static List<ZSetEntry> rangeZSet(RedisObject.ZSetObj zsetObj, long start, long stop) {
        return toZSetEntries(zsetObj.skipList().rangeByIndex(start, stop));
    }

    static List<ZSetEntry> rangeByScoreZSet(RedisObject.ZSetObj zsetObj, double min, double max) {
        return toZSetEntries(zsetObj.skipList().rangeByScore(min, max));
    }

    static OptionalLong rankZSet(RedisObject.ZSetObj zsetObj, Bytes member) {
        Double s = zsetObj.scores().get(member);
        if (s == null) {
            return OptionalLong.empty();
        }
        long r = zsetObj.skipList().rank(member, s);
        return r < 0 ? OptionalLong.empty() : OptionalLong.of(r);
    }

    private static List<ZSetEntry> toZSetEntries(List<SkipList.Entry> entries) {
        List<ZSetEntry> out = new ArrayList<>(entries.size());
        for (SkipList.Entry e : entries) {
            out.add(new ZSetEntry(e.member(), e.score()));
        }
        return List.copyOf(out);
    }
}
