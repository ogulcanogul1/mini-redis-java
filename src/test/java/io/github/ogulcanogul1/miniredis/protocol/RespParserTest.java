package io.github.ogulcanogul1.miniredis.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespParserTest {

    @Test
    void parsesSingleMultibulkCommand() {
        RespParser parser = new RespParser();
        feed(parser, "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n");

        assertThat(parser.next()).isEqualTo(cmd("GET", "key"));
        assertThat(parser.next()).isEqualTo(ParseResult.NEED_MORE);
    }

    @Test
    void parsesByteByByteWithoutReturningEarly() {
        byte[] wire = ascii("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n");
        RespParser parser = new RespParser();

        for (int i = 0; i < wire.length - 1; i++) {
            parser.feed(wire, i, 1);
            assertThat(parser.next()).isEqualTo(ParseResult.NEED_MORE);
        }
        parser.feed(wire, wire.length - 1, 1);
        assertThat(parser.next()).isEqualTo(cmd("SET", "a", "1"));
    }

    @Test
    void parsesEveryTwoWaySplitPoint() {
        byte[] wire = ascii("*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n");
        for (int cut = 0; cut <= wire.length; cut++) {
            RespParser parser = new RespParser();
            parser.feed(wire, 0, cut);
            if (cut < wire.length) {
                assertThat(parser.next()).isEqualTo(ParseResult.NEED_MORE);
            }
            parser.feed(wire, cut, wire.length - cut);
            assertThat(parser.next()).isEqualTo(cmd("ECHO", "hello"));
        }
    }

    @Test
    void parsesPipelinedCommandsInEveryChunkSize() {
        byte[] wire = ascii("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\na\r\n*2\r\n$3\r\nDEL\r\n$1\r\nb\r\n");
        List<ParseResult> expected = List.of(cmd("PING"), cmd("GET", "a"), cmd("DEL", "b"));

        for (int chunk = 1; chunk <= wire.length; chunk++) {
            RespParser parser = new RespParser();
            List<ParseResult> results = new ArrayList<>();
            for (int off = 0; off < wire.length; off += chunk) {
                int len = Math.min(chunk, wire.length - off);
                parser.feed(wire, off, len);
                while (true) {
                    ParseResult r = parser.next();
                    if (r == ParseResult.NEED_MORE) {
                        break;
                    }
                    results.add(r);
                }
            }
            assertThat(results).isEqualTo(expected);
        }
    }

    @Test
    void supportsBinaryAndEmptyBulkStrings() {
        byte[] payload = {'a', '\r', '\n', 0, (byte) 0xFF};
        byte[] prefix = ascii("*3\r\n$3\r\nSET\r\n$0\r\n\r\n$5\r\n");
        byte[] suffix = ascii("\r\n");

        RespParser parser = new RespParser();
        parser.feed(prefix, 0, prefix.length);
        parser.feed(payload, 0, payload.length);
        parser.feed(suffix, 0, suffix.length);

        assertThat(parser.next())
                .isEqualTo(new ParseResult.Command(List.of(Bytes.utf8("SET"), Bytes.EMPTY, Bytes.of(payload))));
    }

    @Test
    void ignoresZeroAndNegativeMultibulkCounts() {
        RespParser parser = new RespParser();
        feed(parser, "*0\r\n*-1\r\n*-5\r\n*1\r\n$4\r\nPING\r\n");

        assertThat(parser.next()).isEqualTo(cmd("PING"));
    }

    @Test
    void skipsTrailingTwoBytesAfterBulkUncheckedLikeRedis() {
        RespParser parser = new RespParser();
        feed(parser, "*1\r\n$4\r\nPINGxx");

        assertThat(parser.next()).isEqualTo(cmd("PING"));
    }

    @Test
    void waitsForByteAfterCrInHeaderEvenWhenLfOnlyPrecedesIt() {
        RespParser parser = new RespParser();
        feed(parser, "*1\r\n$4\nPING\n");
        assertThat(parser.next()).isEqualTo(ParseResult.NEED_MORE);

        RespParser splitAtCr = new RespParser();
        feed(splitAtCr, "*1\r");
        assertThat(splitAtCr.next()).isEqualTo(ParseResult.NEED_MORE);
        feed(splitAtCr, "\n$4\r\nPING\r\n");
        assertThat(splitAtCr.next()).isEqualTo(cmd("PING"));
    }

    @Test
    void reportsAllProtocolErrorMessages() {
        assertError("*abc\r\n", ProtocolErrors.INVALID_MULTIBULK_LENGTH);
        assertError("*+1\r\n", ProtocolErrors.INVALID_MULTIBULK_LENGTH);
        assertError("*1\r\n$abc\r\n", ProtocolErrors.INVALID_BULK_LENGTH);
        assertError("*1\r\n$-1\r\n", ProtocolErrors.INVALID_BULK_LENGTH);
        assertError("*1\r\nfoo\r\n", "ERR Protocol error: expected '$', got 'f'");
        assertError("*1\r\n\n\r\n", "ERR Protocol error: expected '$', got ' '");
    }

    @Test
    void enforcesLimitsAndBoundaryValues() {
        RespLimits small = new RespLimits(2, 4, 8);

        RespParser ok = new RespParser(small);
        feed(ok, "*2\r\n$4\r\nPING\r\n$4\r\n1234\r\n");
        assertThat(ok.next()).isEqualTo(cmd("PING", "1234"));

        assertError(new RespParser(small), "*3\r\n", ProtocolErrors.INVALID_MULTIBULK_LENGTH);
        assertError(new RespParser(small), "*1\r\n$5\r\n", ProtocolErrors.INVALID_BULK_LENGTH);
        assertError(new RespParser(small), "*12345678", ProtocolErrors.TOO_BIG_MBULK_COUNT);
        assertError(new RespParser(small), "*1\r\n$12345678", ProtocolErrors.TOO_BIG_BULK_COUNT);
    }

    @Test
    void protocolErrorIsTerminal() {
        RespParser parser = new RespParser();
        feed(parser, "*bad\r\n");

        ParseResult first = parser.next();
        assertThat(first).isEqualTo(new ParseResult.ProtocolError(ProtocolErrors.INVALID_MULTIBULK_LENGTH));
        assertThat(parser.next()).isEqualTo(first);

        byte[] more = ascii("*1\r\n$4\r\nPING\r\n");
        assertThatThrownBy(() -> parser.feed(more, 0, more.length)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void largeBulkInSmallPiecesShrinksBufferAfterwards() {
        int size = 1024 * 1024;
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 'a');

        RespParser parser = new RespParser();
        feed(parser, "*1\r\n$" + size + "\r\n");
        for (int off = 0; off < size; off += 4096) {
            parser.feed(payload, off, Math.min(4096, size - off));
            assertThat(parser.next()).isEqualTo(ParseResult.NEED_MORE);
        }
        feed(parser, "\r\n");

        ParseResult result = parser.next();
        assertThat(result).isInstanceOf(ParseResult.Command.class);
        assertThat(((ParseResult.Command) result).argv().getFirst().length()).isEqualTo(size);
        assertThat(parser.capacity()).isLessThanOrEqualTo(RespParser.MAX_IDLE_CAPACITY);
    }

    private static void assertError(String input, String expectedMessage) {
        assertError(new RespParser(), input, expectedMessage);
    }

    private static void assertError(RespParser parser, String input, String expectedMessage) {
        feed(parser, input);
        assertThat(parser.next()).isEqualTo(new ParseResult.ProtocolError(expectedMessage));
    }

    private static ParseResult.Command cmd(String... parts) {
        return new ParseResult.Command(Arrays.stream(parts).map(Bytes::utf8).toList());
    }

    private static void feed(RespParser parser, String s) {
        byte[] bytes = ascii(s);
        parser.feed(bytes, 0, bytes.length);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
