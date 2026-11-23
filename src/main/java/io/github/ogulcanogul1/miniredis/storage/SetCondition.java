package io.github.ogulcanogul1.miniredis.storage;

/** Write precondition for {@link Keyspace#set(io.github.ogulcanogul1.miniredis.ds.Bytes, io.github.ogulcanogul1.miniredis.ds.Bytes, SetCondition, Long)}. */
public enum SetCondition {
    /** Always write the key. */
    ALWAYS,
    /** Write only if the key does not currently exist ({@code NX}). */
    IF_ABSENT,
    /** Write only if the key already exists ({@code XX}). */
    IF_PRESENT
}
