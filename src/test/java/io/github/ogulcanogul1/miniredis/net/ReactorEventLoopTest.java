package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ogulcanogul1.miniredis.commands.CommandContext;
import io.github.ogulcanogul1.miniredis.commands.CommandTable;
import io.github.ogulcanogul1.miniredis.commands.Dispatcher;
import io.github.ogulcanogul1.miniredis.commands.ServerStats;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import io.github.ogulcanogul1.miniredis.storage.Keyspaces;
import io.github.ogulcanogul1.miniredis.util.Clock;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ReactorEventLoopTest {

    @Test
    void handlesPartialReadsPipeliningQuitAndProtocolErrorsOnSingleLoop() throws Exception {
        ServerStats stats = new ServerStats();
        Dispatcher d = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory(), stats));
        AtomicInteger closedCallbacks = new AtomicInteger();

        try (ServerSocketChannel listener = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                ReactorEventLoop loop = new ReactorEventLoop(
                        "test-reactor",
                        d,
                        RespLimits.defaults(),
                        stats,
                        Clock.system(),
                        16 * 1024,
                        0,
                        closedCallbacks::incrementAndGet)) {
            loop.start();
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (TestRespClient c1 = new TestRespClient("127.0.0.1", port);
                    TestRespClient c2 = new TestRespClient("127.0.0.1", port)) {
                loop.register(listener.accept());
                loop.register(listener.accept());

                // Split a single command across two TCP sends.
                c1.sendAscii("*2\r\n$4\r\nEC");
                Thread.sleep(20);
                c1.sendAscii("HO\r\n$5\r\nhello\r\n");
                assertThat(new String(c1.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("$5\r\nhello\r\n");

                // Pipelined commands in one packet.
                c2.sendAscii("SET k v\r\nGET k\r\n");
                assertThat(new String(c2.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+OK\r\n");
                assertThat(new String(c2.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("$1\r\nv\r\n");

                // Protocol error closes only c1; c2 continues working on the exact same loop.
                c1.sendAscii("*abc\r\nPING\r\n");
                assertThat(new String(c1.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("-ERR Protocol error: invalid multibulk length\r\n");
                assertThat(c1.readNextByte()).isEqualTo(-1);

                c2.sendCommand("PING");
                assertThat(new String(c2.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");

                c2.sendCommand("QUIT");
                assertThat(new String(c2.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+OK\r\n");
                assertThat(c2.readNextByte()).isEqualTo(-1);
            }
        }
        assertThat(closedCallbacks.get()).isEqualTo(2);
        assertThat(stats.connectedClients()).isZero();
    }

    @Test
    void dropsConnectionExceedingOutputBufferLimitAndSurvivesIdleAndRuntimeErrors() throws Exception {
        ServerStats stats = new ServerStats();
        Dispatcher d = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory(), stats));
        AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
        Clock fakeClock = new Clock() {
            @Override
            public long millis() {
                return 0;
            }

            @Override
            public long nanoTime() {
                return nanoTime.get();
            }
        };

        Logger logger = (Logger) LoggerFactory.getLogger(NioConnection.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (ServerSocketChannel listener = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                ReactorEventLoop loop = new ReactorEventLoop(
                        "test-reactor-2",
                        d,
                        RespLimits.defaults(),
                        stats,
                        fakeClock,
                        16,
                        TimeUnit.MILLISECONDS.toNanos(50),
                        () -> {})) {
            loop.start();
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            try (TestRespClient oversized = new TestRespClient("127.0.0.1", port);
                    TestRespClient idleClient = new TestRespClient("127.0.0.1", port)) {
                loop.register(listener.accept());
                loop.register(listener.accept());

                // Reply for ECHO "0123456789abcdefghij" exceeds maxOutputBytes=16.
                oversized.sendCommand("ECHO", "0123456789abcdefghij");
                assertThat(oversized.readNextByte()).isEqualTo(-1);
                assertThat(appender.list)
                        .anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));

                // Healthy client still responds before idle timeout fires.
                idleClient.sendCommand("PING");
                assertThat(new String(idleClient.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");

                // Advance fake monotonic clock beyond idle timeout (50 ms) after flushOutbound updates lastActiveNanos.
                Thread.sleep(30);
                nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
                Thread.sleep(30);
                nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
                assertThat(idleClient.readNextByte()).isEqualTo(-1);
            }

            // Unexpected RuntimeException on a malformed SelectionKey closes that channel cleanly without throwing.
            Logger loopLogger = (Logger) LoggerFactory.getLogger(ReactorEventLoop.class);
            ListAppender<ILoggingEvent> loopAppender = new ListAppender<>();
            loopAppender.start();
            loopLogger.addAppender(loopAppender);
            try (SocketChannel orphan = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
                    var accepted = listener.accept();
                    var selector = java.nio.channels.Selector.open()) {
                accepted.configureBlocking(false);
                SelectionKey brokenKey = accepted.register(selector, SelectionKey.OP_READ);
                brokenKey.attach("invalid-attachment");
                loop.handleSelectedKey(brokenKey);
                assertThat(brokenKey.isValid()).isFalse();
                assertThat(loopAppender.list)
                        .anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
                orphan.write(ByteBuffer.allocate(0));
            } finally {
                loopLogger.detachAppender(loopAppender);
                loopAppender.stop();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(stats.connectedClients()).isZero();
    }
}
