package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class VirtualThreadServerTest {

    @Test
    void rejectsInvalidArguments() {
        ServerStats stats = new ServerStats();
        Dispatcher d = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory(), stats));
        assertThatThrownBy(() -> new VirtualThreadServer("127.0.0.1", 0, d, RespLimits.defaults(), 0, stats))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VirtualThreadServer("127.0.0.1", 70_000, d, RespLimits.defaults(), 10, stats))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void servesConcurrentClientsAndClosesCleanly() throws Exception {
        ServerStats stats = new ServerStats();
        Dispatcher d = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory(), stats));
        int clients = 16;
        int increments = 100;
        try (VirtualThreadServer server =
                        new VirtualThreadServer("127.0.0.1", 0, d, RespLimits.defaults(), 100, stats);
                ExecutorService callers = Executors.newFixedThreadPool(clients)) {
            server.start();
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(clients);
            AtomicInteger errors = new AtomicInteger();

            for (int c = 0; c < clients; c++) {
                callers.execute(() -> {
                    try (TestRespClient client = new TestRespClient("127.0.0.1", server.port())) {
                        gate.await();
                        for (int i = 0; i < increments; i++) {
                            client.sendCommand("INCR", "vt:hits");
                            client.readFrame();
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            gate.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isZero();

            try (TestRespClient check = new TestRespClient("127.0.0.1", server.port())) {
                check.sendCommand("GET", "vt:hits");
                String expected = Integer.toString(clients * increments);
                assertThat(new String(check.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
            }

            server.stop();
            server.awaitTermination();
            assertThat(stats.connectedClients()).isZero();
        }
    }

    @Test
    void closesConnectionsExceedingMaxClientsAndLogsWarning() throws Exception {
        ServerStats stats = new ServerStats();
        Dispatcher d = new Dispatcher(CommandTable.standard(), new CommandContext(Keyspaces.inMemory(), stats));
        Logger logger = (Logger) LoggerFactory.getLogger(VirtualThreadServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (VirtualThreadServer server = new VirtualThreadServer("127.0.0.1", 0, d, RespLimits.defaults(), 2, stats)) {
            server.start();
            try (TestRespClient c1 = new TestRespClient("127.0.0.1", server.port());
                    TestRespClient c2 = new TestRespClient("127.0.0.1", server.port())) {
                c1.sendCommand("PING");
                assertThat(new String(c1.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");
                c2.sendCommand("PING");
                assertThat(new String(c2.readFrame(), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");

                try (TestRespClient rejected = new TestRespClient("127.0.0.1", server.port())) {
                    assertThat(rejected.readNextByte()).isEqualTo(-1);
                }
                assertThat(appender.list).anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("maxclients");
                });
            }
            server.stop();
            server.awaitTermination();
            assertThat(stats.connectedClients()).isZero();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
