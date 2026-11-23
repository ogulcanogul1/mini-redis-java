package io.github.ogulcanogul1.miniredis.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Minimal file channel abstraction for the AOF so write/fsync failures and crash truncation can be
 * tested deterministically with fakes without filling a real disk (PLAN §3 Faz 8.1).
 */
public interface AofChannel extends AutoCloseable {

    /** Writes all remaining bytes in {@code src} at the current append position. */
    void writeAll(ByteBuffer src) throws IOException;

    /** Flushes buffered OS pages to the storage device ({@code FileChannel.force}). */
    void force(boolean metaData) throws IOException;

    /** Returns the current size of the file in bytes. */
    long size() throws IOException;

    /** Reads bytes starting at {@code position} into {@code dst}; returns bytes read or {@code -1} at EOF. */
    int read(ByteBuffer dst, long position) throws IOException;

    /** Truncates the file to {@code size} bytes (used to trim a partial tail record). */
    void truncate(long size) throws IOException;

    @Override
    void close() throws IOException;
}
