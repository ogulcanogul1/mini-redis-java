package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.util.Numbers;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Simple blocking RESP2 test client used by integration and differential tests. */
final class TestRespClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    TestRespClient(String host, int port) throws IOException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 5000);
        this.socket.setSoTimeout(5000);
        this.socket.setTcpNoDelay(true);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    void send(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    void sendAscii(String s) throws IOException {
        send(s.getBytes(StandardCharsets.US_ASCII));
    }

    void sendCommand(String... args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] raw = arg.getBytes(StandardCharsets.UTF_8);
            buf.writeBytes(("$" + raw.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buf.writeBytes(raw);
            buf.write('\r');
            buf.write('\n');
        }
        send(buf.toByteArray());
    }

    /** Reads one complete RESP2 reply and returns its exact wire bytes. */
    byte[] readFrame() throws IOException {
        ByteArrayOutputStream outFrame = new ByteArrayOutputStream();
        readFrameInto(outFrame);
        return outFrame.toByteArray();
    }

    /** Reads the elements of one RESP2 array reply as raw wire frames. */
    List<byte[]> readArrayElementFrames() throws IOException {
        int type = in.read();
        if (type != '*') {
            throw new IOException("expected '*' array reply, got: " + type);
        }
        byte[] line = readLineBytes();
        long count = Numbers.parseLong(line, 0, line.length).orElseThrow(() -> new IOException("invalid array length"));
        if (count < 0) {
            return List.of();
        }
        List<byte[]> elements = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            elements.add(readFrame());
        }
        return elements;
    }

    /** Reads a single byte from the stream (used to assert EOF = {@code -1}). */
    int readNextByte() throws IOException {
        return in.read();
    }

    private void readFrameInto(ByteArrayOutputStream outFrame) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("unexpected end of stream");
        }
        outFrame.write(type);
        byte[] line = readLineBytes();
        outFrame.writeBytes(line);
        outFrame.write('\r');
        outFrame.write('\n');

        switch (type) {
            case '+', '-', ':' -> {
                // Single-line replies are complete after CRLF.
            }
            case '$' -> {
                long len = Numbers.parseLong(line, 0, line.length)
                        .orElseThrow(() -> new IOException("invalid bulk length"));
                if (len >= 0) {
                    byte[] payload = in.readNBytes((int) len + 2);
                    if (payload.length < len + 2) {
                        throw new EOFException("truncated bulk string");
                    }
                    outFrame.writeBytes(payload);
                }
            }
            case '*' -> {
                long count = Numbers.parseLong(line, 0, line.length)
                        .orElseThrow(() -> new IOException("invalid array length"));
                for (long i = 0; i < count; i++) {
                    readFrameInto(outFrame);
                }
            }
            default -> throw new IOException("unexpected RESP type byte: " + type);
        }
    }

    private byte[] readLineBytes() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("EOF before CRLF");
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = line.toByteArray();
                // Drop the trailing '\r' already appended to line.
                return java.util.Arrays.copyOf(bytes, bytes.length - 1);
            }
            line.write(b);
            prev = b;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
