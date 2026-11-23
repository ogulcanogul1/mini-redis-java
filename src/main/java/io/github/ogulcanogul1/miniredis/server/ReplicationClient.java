package io.github.ogulcanogul1.miniredis.server;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.persistence.RdbCodec;
import io.github.ogulcanogul1.miniredis.protocol.ParseResult;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.protocol.RespParser;
import io.github.ogulcanogul1.miniredis.storage.Keyspace;
import io.github.ogulcanogul1.miniredis.storage.SnapshotEntry;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background replication client connecting a replica to its master, performing {@code PSYNC} full
 * RDB resync, and applying streamed {@code WRITE} commands in order.
 */
final class ReplicationClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplicationClient.class);
    private static final long RECONNECT_DELAY_MS = 200;

    private final String masterHost;
    private final int masterPort;
    private final int localPort;
    private final Keyspace keyspace;
    private final Dispatcher applyDispatcher;
    private final RespLimits limits;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket activeSocket;
    private Thread syncThread;

    ReplicationClient(
            String masterHost,
            int masterPort,
            int localPort,
            Keyspace keyspace,
            Dispatcher applyDispatcher,
            RespLimits limits) {
        this.masterHost = Objects.requireNonNull(masterHost, "masterHost");
        this.masterPort = masterPort;
        this.localPort = localPort;
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.applyDispatcher = Objects.requireNonNull(applyDispatcher, "applyDispatcher");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        syncThread = Thread.ofPlatform().name("replica-sync").daemon(true).start(this::syncLoop);
    }

    @Override
    public void close() {
        running.set(false);
        Socket s = activeSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing during shutdown.
            }
        }
        Thread t = syncThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void syncLoop() {
        while (running.get()) {
            try (Socket s = new Socket(masterHost, masterPort)) {
                activeSocket = s;
                s.setTcpNoDelay(true);
                BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();

                writeCommand(out, "PING");
                String pong = readLine(in);
                if (!"+PONG".equals(pong)) {
                    throw new IOException("unexpected PING reply from master: " + pong);
                }

                writeCommand(out, "REPLCONF", "listening-port", Integer.toString(localPort));
                String ok = readLine(in);
                if (!"+OK".equals(ok)) {
                    throw new IOException("unexpected REPLCONF reply from master: " + ok);
                }

                writeCommand(out, "PSYNC", "?", "-1");
                String arrayHdr = readLine(in);
                if (!"*2".equals(arrayHdr)) {
                    throw new IOException("unexpected PSYNC header from master: " + arrayHdr);
                }
                String fullResync = readLine(in);
                if (fullResync == null || !fullResync.startsWith("+FULLRESYNC ")) {
                    throw new IOException("unexpected PSYNC reply from master: " + fullResync);
                }

                byte[] rdb = readBulkBytes(in);
                List<SnapshotEntry> snapshot = RdbCodec.decode(rdb);
                keyspace.flush();
                keyspace.restoreSnapshot(snapshot);
                log.info("MASTER <-> REPLICA sync finished with {} keys", snapshot.size());

                RespParser parser = new RespParser(limits);
                byte[] buf = new byte[16 * 1024];
                while (running.get()) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    parser.feed(buf, 0, n);
                    while (true) {
                        ParseResult res = parser.next();
                        if (res instanceof ParseResult.Command cmd) {
                            applyDispatcher.dispatch(cmd.argv());
                        } else if (res instanceof ParseResult.NeedMore) {
                            break;
                        } else if (res instanceof ParseResult.ProtocolError err) {
                            throw new IOException("protocol error in replication stream: " + err.message());
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (running.get()) {
                    log.debug("replication connection error ({}:{}): {}", masterHost, masterPort, e.getMessage());
                }
            } finally {
                activeSocket = null;
            }
            if (running.get()) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static void writeCommand(OutputStream out, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            sb.setLength(0);
            out.write(bytes);
            out.write('\r');
            out.write('\n');
        }
        out.flush();
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return baos.toString(StandardCharsets.ISO_8859_1);
                }
                throw new IOException("expected LF after CR");
            }
            baos.write(b);
        }
        return null;
    }

    private static byte[] readBulkBytes(BufferedInputStream in) throws IOException {
        String header = readLine(in);
        if (header == null || !header.startsWith("$")) {
            throw new IOException("expected bulk header, got: " + header);
        }
        int len = Integer.parseInt(header.substring(1));
        byte[] data = in.readNBytes(len);
        if (data.length != len) {
            throw new IOException("unexpected EOF reading RDB bulk payload");
        }
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("expected CRLF after RDB bulk payload");
        }
        return data;
    }
}
