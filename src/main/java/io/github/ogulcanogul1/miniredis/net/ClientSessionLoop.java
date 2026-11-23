package io.github.ogulcanogul1.miniredis.net;

import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.protocol.RespSession;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/** Shared blocking client read/dispatch/flush loop used by all blocking {@link ServerBackend} implementations. */
final class ClientSessionLoop {

    static final int READ_BUFFER_SIZE = 16 * 1024;
    static final long STOP_TIMEOUT_SECONDS = 5;

    private ClientSessionLoop() {}

    static void serve(
            long clientId,
            Socket client,
            Dispatcher dispatcher,
            RespLimits limits,
            ServerStats stats,
            BooleanSupplier isRunning,
            Logger log) {
        String remote = String.valueOf(client.getRemoteSocketAddress());
        stats.clientConnected();
        log.debug("client={} addr={} connected", clientId, remote);
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream());
            byte[] readBuf = new byte[READ_BUFFER_SIZE];
            boolean loopback = isLoopback(client.getRemoteSocketAddress());
            RespSession session = new RespSession(dispatcher, limits, loopback);

            while (isRunning.getAsBoolean() && !session.shouldClose()) {
                int n = in.read(readBuf);
                if (n < 0) {
                    break;
                }
                byte[] reply = session.onData(readBuf, 0, n);
                if (reply.length > 0) {
                    out.write(reply);
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (isRunning.getAsBoolean()) {
                log.debug("client={} addr={} connection error: {}", clientId, remote, e.getMessage());
            }
        } finally {
            closeQuietly(client);
            stats.clientDisconnected();
            log.debug("client={} addr={} closed", clientId, remote);
        }
    }

    static boolean isLoopback(SocketAddress addr) {
        if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().isLoopbackAddress();
        }
        return true;
    }

    static void closeQuietly(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ignored) {
                // Closing during shutdown or cleanup; nothing actionable to do.
            }
        }
    }
}
