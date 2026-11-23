package io.github.ogulcanogul1.miniredis.storage;

/** Result of an atomic {@link Keyspace#incrBy} update (K12: domain outcome as a value). */
public sealed interface IncrResult permits IncrResult.Ok, IncrResult.NotAnInteger, IncrResult.Overflow {

    record Ok(long value) implements IncrResult {}

    record NotAnInteger() implements IncrResult {}

    record Overflow() implements IncrResult {}
}
