package io.github.ogulcanogul1.miniredis.net;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.server.RedisServer;
import io.github.ogulcanogul1.miniredis.server.ServerConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Differential test (PLAN §0, K11): sends identical raw byte streams to real Redis 7.0.15 and to
 * our {@link RedisServer}, and asserts byte-for-byte identical RESP replies. Run with {@code mvn
 * -B verify -Pcompat}.
 */
class RedisCompatIT {

    @Test
    void matchesRealRedisByteForByte() throws Exception {
        String refHost = System.getProperty("redis.ref.host", "127.0.0.1");
        int refPort = Integer.getInteger("redis.ref.port", 6390);

        List<Scenario> scenarios = parseScenarios(loadResource("/compat/faz-03.txt"));
        assertThat(scenarios).isNotEmpty();

        try (RedisServer ours = new RedisServer(ServerConfig.onPort(0))) {
            ours.start();
            for (Scenario scenario : scenarios) {
                flushBoth(refHost, refPort, ours.port());
                runScenario(scenario, refHost, refPort, "127.0.0.1", ours.port());
            }
        }
    }

    private static void flushBoth(String refHost, int refPort, int ourPort) throws IOException {
        for (Target target : List.of(new Target(refHost, refPort), new Target("127.0.0.1", ourPort))) {
            try (TestRespClient client = new TestRespClient(target.host(), target.port())) {
                client.sendCommand("FLUSHDB");
                assertThat(client.readFrame()).isEqualTo("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    private static void runScenario(Scenario scenario, String refHost, int refPort, String ourHost, int ourPort)
            throws IOException {
        try (TestRespClient ref = new TestRespClient(refHost, refPort);
                TestRespClient ours = new TestRespClient(ourHost, ourPort)) {
            for (Step step : scenario.steps()) {
                ref.send(step.payload());
                ours.send(step.payload());
                switch (step.kind()) {
                    case SEND -> {
                        for (int i = 0; i < step.expectedFrames(); i++) {
                            byte[] refReply = ref.readFrame();
                            byte[] ourReply = ours.readFrame();
                            assertThat(ourReply)
                                    .as("scenario '%s' frame %d", scenario.name(), i)
                                    .isEqualTo(refReply);
                        }
                    }
                    case SEND_CLOSE -> {
                        for (int i = 0; i < step.expectedFrames(); i++) {
                            byte[] refReply = ref.readFrame();
                            byte[] ourReply = ours.readFrame();
                            assertThat(ourReply)
                                    .as("scenario '%s' closing frame %d", scenario.name(), i)
                                    .isEqualTo(refReply);
                        }
                        assertThat(ref.readNextByte())
                                .as("real Redis did not close in '%s'", scenario.name())
                                .isEqualTo(-1);
                        assertThat(ours.readNextByte())
                                .as("our server did not close in '%s'", scenario.name())
                                .isEqualTo(-1);
                    }
                    case SEND_UNORDERED -> {
                        List<String> refElems = sortedFrames(ref.readArrayElementFrames());
                        List<String> ourElems = sortedFrames(ours.readArrayElementFrames());
                        assertThat(ourElems)
                                .as("scenario '%s'", scenario.name())
                                .isEqualTo(refElems);
                    }
                }
            }
        }
    }

    private static List<String> sortedFrames(List<byte[]> frames) {
        return frames.stream()
                .map(b -> new String(b, StandardCharsets.ISO_8859_1))
                .sorted()
                .toList();
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = RedisCompatIT.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Scenario> parseScenarios(String content) {
        List<Scenario> scenarios = new ArrayList<>();
        String currentName = null;
        List<Step> currentSteps = new ArrayList<>();

        for (String rawLine : content.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("--- ")) {
                if (currentName != null) {
                    scenarios.add(new Scenario(currentName, List.copyOf(currentSteps)));
                    currentSteps.clear();
                }
                currentName = line.substring(4).trim();
            } else if (line.startsWith("SEND ")) {
                String rest = line.substring(5);
                int space = rest.indexOf(' ');
                int frames = Integer.parseInt(rest.substring(0, space));
                byte[] payload = unescape(rest.substring(space + 1));
                currentSteps.add(new Step(Kind.SEND, frames, payload));
            } else if (line.startsWith("SEND_CLOSE ")) {
                String rest = line.substring(11);
                int space = rest.indexOf(' ');
                int frames = Integer.parseInt(rest.substring(0, space));
                byte[] payload = unescape(rest.substring(space + 1));
                currentSteps.add(new Step(Kind.SEND_CLOSE, frames, payload));
            } else if (line.startsWith("SEND_UNORDERED ")) {
                byte[] payload = unescape(line.substring(15));
                currentSteps.add(new Step(Kind.SEND_UNORDERED, 1, payload));
            } else {
                throw new IllegalArgumentException("unrecognized compat line: " + line);
            }
        }
        if (currentName != null) {
            scenarios.add(new Scenario(currentName, List.copyOf(currentSteps)));
        }
        return scenarios;
    }

    private static byte[] unescape(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'r' -> {
                        out.write('\r');
                        i++;
                    }
                    case 'n' -> {
                        out.write('\n');
                        i++;
                    }
                    case 't' -> {
                        out.write('\t');
                        i++;
                    }
                    case '\\' -> {
                        out.write('\\');
                        i++;
                    }
                    case 'x' -> {
                        int val = Integer.parseInt(s.substring(i + 2, i + 4), 16);
                        out.write(val);
                        i += 3;
                    }
                    default -> {
                        // Preserve other escapes (e.g. \b, \a, \', \\) literally so the server's inline parser sees
                        // them.
                        out.write('\\');
                    }
                }
            } else {
                out.write((byte) c);
            }
        }
        return out.toByteArray();
    }

    private enum Kind {
        SEND,
        SEND_CLOSE,
        SEND_UNORDERED
    }

    private record Step(Kind kind, int expectedFrames, byte[] payload) {}

    private record Scenario(String name, List<Step> steps) {}

    private record Target(String host, int port) {}
}
