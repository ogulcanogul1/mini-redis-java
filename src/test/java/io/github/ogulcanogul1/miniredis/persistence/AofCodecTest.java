package io.github.ogulcanogul1.miniredis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.protocol.RespLimits;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class AofCodecTest {

    @Test
    void encodeAndDecodeRoundTripPreservesBinaryData() {
        List<Bytes> cmd1 = List.of(Bytes.ascii("SET"), Bytes.ascii("k1"), Bytes.of(new byte[] {0, 13, 10, -1}));
        List<Bytes> cmd2 = List.of(Bytes.ascii("DEL"), Bytes.ascii("k2"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(AofCodec.encodeCommand(cmd1));
        out.writeBytes(AofCodec.encodeCommand(cmd2));
        byte[] all = out.toByteArray();

        AofCodec.DecodeResult decoded = AofCodec.decodeAll(all, RespLimits.defaults());
        assertThat(decoded.truncatedTail()).isFalse();
        assertThat(decoded.validBytes()).isEqualTo(all.length);
        assertThat(decoded.commands()).containsExactly(cmd1, cmd2);
    }

    @Test
    void partialTailIsReportedWithoutFailing() {
        byte[] cmd1 = AofCodec.encodeCommand(List.of(Bytes.ascii("SET"), Bytes.ascii("a"), Bytes.ascii("1")));
        byte[] cmd2 = AofCodec.encodeCommand(List.of(Bytes.ascii("SET"), Bytes.ascii("b"), Bytes.ascii("2")));
        byte[] combined = new byte[cmd1.length + cmd2.length - 3];
        System.arraycopy(cmd1, 0, combined, 0, cmd1.length);
        System.arraycopy(cmd2, 0, combined, cmd1.length, cmd2.length - 3);

        AofCodec.DecodeResult decoded = AofCodec.decodeAll(combined, RespLimits.defaults());
        assertThat(decoded.truncatedTail()).isTrue();
        assertThat(decoded.validBytes()).isEqualTo(cmd1.length);
        assertThat(decoded.commands()).hasSize(1);
    }

    @Test
    void midFileCorruptionFailsFast() {
        byte[] corrupt = "*abc\r\n$3\r\nSET\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> AofCodec.decodeAll(corrupt, RespLimits.defaults()))
                .isInstanceOf(AofException.class);
    }
}
