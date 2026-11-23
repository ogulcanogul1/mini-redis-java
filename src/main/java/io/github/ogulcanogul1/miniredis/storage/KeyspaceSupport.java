package io.github.ogulcanogul1.miniredis.storage;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Shared validation and integer arithmetic helper used by {@link Keyspace} implementations. */
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
}
