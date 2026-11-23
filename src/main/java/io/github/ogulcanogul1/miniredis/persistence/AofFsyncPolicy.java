package io.github.ogulcanogul1.miniredis.persistence;

/**
 * Append-only file fsync policy (PLAN §3 Faz 8.1).
 *
 * <ul>
 *   <li>{@link #ALWAYS}: {@code force()} after every write command (safest, slowest)
 *   <li>{@link #EVERYSEC}: {@code force()} once per second on a background scheduler
 *   <li>{@link #NO}: let the OS page cache decide when to flush
 * </ul>
 */
public enum AofFsyncPolicy {
    ALWAYS,
    EVERYSEC,
    NO
}
