package io.github.ogulcanogul1.miniredis.server;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ogulcanogul1.miniredis.Main;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RedisServerTest {

    @Test
    void startsServesCommandsAndStopsIdempotently() throws Exception {
        try (RedisServer server = new RedisServer(ServerConfig.onPort(0))) {
            assertThat(server.port()).isEqualTo(-1);
            server.start();
            assertThat(server.port()).isPositive();

            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                InputStream in = socket.getInputStream();
                assertThat(new String(in.readNBytes(7), StandardCharsets.US_ASCII))
                        .isEqualTo("+PONG\r\n");
            }

            server.stop();
            server.awaitTermination();
            server.stop();
        }
    }

    @Test
    void mainRunSupportsVersionAndFailsFastOnErrors() throws IOException {
        assertThat(Main.run(new String[] {"--version"})).isEqualTo(0);

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            assertThat(Main.run(new String[] {"--unknown-flag", "1"})).isEqualTo(2);
            assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));

            appender.list.clear();
            try (ServerSocket blocker = new ServerSocket(0)) {
                assertThat(Main.run(new String[] {"--port", Integer.toString(blocker.getLocalPort())}))
                        .isEqualTo(1);
                assertThat(appender.list)
                        .anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
