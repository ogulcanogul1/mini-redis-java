package io.github.ogulcanogul1.miniredis.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** {@link AofChannel} backed by a {@link FileChannel}. */
final class FileAofChannel implements AofChannel {

    private final FileChannel channel;

    FileAofChannel(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.channel.position(this.channel.size());
    }

    @Override
    public void writeAll(ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            channel.write(src);
        }
    }

    @Override
    public void force(boolean metaData) throws IOException {
        channel.force(metaData);
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return channel.read(dst, position);
    }

    @Override
    public void truncate(long size) throws IOException {
        channel.truncate(size);
        channel.position(size);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
