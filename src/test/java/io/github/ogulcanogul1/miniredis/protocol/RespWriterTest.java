package io.github.ogulcanogul1.miniredis.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.commands.Reply;
import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespWriterTest {

    @Test
    void encodesSimpleStrings() {
        assertThat(RespWriter.encode(Reply.OK)).isEqualTo(ascii("+OK\r\n"));
        assertThat(RespWriter.encode(Reply.simple(""))).isEqualTo(ascii("+\r\n"));
    }

    @Test
    void encodesErrorsWithIso88591() {
        assertThat(RespWriter.encode(Reply.error("ERR syntax error"))).isEqualTo(ascii("-ERR syntax error\r\n"));

        byte[] raw = RespWriter.encode(Reply.error("Ã©"));
        assertThat(raw).isEqualTo(new byte[] {'-', (byte) 0xC3, (byte) 0xA9, '\r', '\n'});
    }

    @Test
    void encodesIntegers() {
        assertThat(RespWriter.encode(Reply.integer(0))).isEqualTo(ascii(":0\r\n"));
        assertThat(RespWriter.encode(Reply.integer(-2))).isEqualTo(ascii(":-2\r\n"));
        assertThat(RespWriter.encode(Reply.integer(Long.MIN_VALUE))).isEqualTo(ascii(":" + Long.MIN_VALUE + "\r\n"));
    }

    @Test
    void encodesBulkStringsAndNullBulk() {
        assertThat(RespWriter.encode(Reply.bulk(Bytes.utf8("hello")))).isEqualTo(ascii("$5\r\nhello\r\n"));
        assertThat(RespWriter.encode(Reply.bulk(Bytes.EMPTY))).isEqualTo(ascii("$0\r\n\r\n"));
        assertThat(RespWriter.encode(Reply.NIL)).isEqualTo(ascii("$-1\r\n"));

        byte[] utf8 = RespWriter.encode(Reply.bulk(Bytes.utf8("é")));
        assertThat(utf8).isEqualTo(new byte[] {'$', '2', '\r', '\n', (byte) 0xC3, (byte) 0xA9, '\r', '\n'});

        byte[] binary = {'a', '\r', '\n', 0, (byte) 0xFF};
        byte[] expected = {'$', '5', '\r', '\n', 'a', '\r', '\n', 0, (byte) 0xFF, '\r', '\n'};
        assertThat(RespWriter.encode(Reply.bulk(Bytes.of(binary)))).isEqualTo(expected);
    }

    @Test
    void encodesEmptyMixedAndNestedArrays() {
        assertThat(RespWriter.encode(Reply.array(List.of()))).isEqualTo(ascii("*0\r\n"));

        Reply mixed = Reply.array(List.of(Reply.OK, Reply.integer(3), Reply.NIL, Reply.bulk(Bytes.utf8("hi"))));
        assertThat(RespWriter.encode(mixed)).isEqualTo(ascii("*4\r\n+OK\r\n:3\r\n$-1\r\n$2\r\nhi\r\n"));

        Reply nested = Reply.array(List.of(Reply.array(List.of(Reply.integer(1))), Reply.error("ERR x")));
        assertThat(RespWriter.encode(nested)).isEqualTo(ascii("*2\r\n*1\r\n:1\r\n-ERR x\r\n"));
    }

    @Test
    void nullReplyThrows() {
        assertThatThrownBy(() -> RespWriter.encode(null)).isInstanceOf(NullPointerException.class);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
