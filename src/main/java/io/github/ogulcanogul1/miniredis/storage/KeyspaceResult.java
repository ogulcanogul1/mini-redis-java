package io.github.ogulcanogul1.miniredis.storage;

/**
 * Typed result returned by {@link Keyspace} operations that distinguish a type mismatch ({@code
 * WRONGTYPE}) from a normal return value without leaking {@code Reply} into {@code storage} (K1, K3).
 */
public sealed interface KeyspaceResult<T> permits KeyspaceResult.Ok, KeyspaceResult.WrongType {

    record Ok<T>(T value) implements KeyspaceResult<T> {}

    record WrongType<T>() implements KeyspaceResult<T> {}

    static <T> KeyspaceResult<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> KeyspaceResult<T> wrongType() {
        return new WrongType<>();
    }
}
