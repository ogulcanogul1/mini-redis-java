package io.github.ogulcanogul1.miniredis.ds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BytesTest {

    @Test
    void emptyHasLengthZeroAndEqualsEmptyConstant() {
        assertThat(Bytes.EMPTY.length()).isZero();
        assertThat(Bytes.of(new byte[0])).isEqualTo(Bytes.EMPTY);
    }

    @Test
    void nullByteIsPartOfTheContent() {
        Bytes b = Bytes.of(new byte[] {'a', 0, 'b'});

        assertThat(b.length()).isEqualTo(3);
        assertThat(b.byteAt(1)).isZero();
    }

    @Test
    void changingSourceArrayDoesNotChangeBytes() {
        byte[] source = {'a', 'b'};
        Bytes b = Bytes.of(source);

        source[0] = 'z';

        assertThat(b).isEqualTo(Bytes.of(new byte[] {'a', 'b'}));
    }

    @Test
    void changingReturnedArrayDoesNotChangeBytes() {
        Bytes b = Bytes.of(new byte[] {'a', 'b'});

        byte[] copy = b.toByteArray();
        copy[0] = 'z';

        assertThat(b).isEqualTo(Bytes.of(new byte[] {'a', 'b'}));
    }

    @Test
    void byteAtOutOfRangeThrows() {
        Bytes b = Bytes.utf8("ab");

        assertThatThrownBy(() -> b.byteAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> b.byteAt(b.length())).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void equalContentMeansEqualAndSameHash() {
        Bytes a = Bytes.utf8("key");
        Bytes b = Bytes.utf8("key");

        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equalsIsFalseForNullAndOtherTypes() {
        Bytes b = Bytes.utf8("key");

        assertThat(b).isNotEqualTo(null);
        assertThat(b).isNotEqualTo("key");
    }

    @Test
    void hashMapFindsKeyByContentFromAnotherInstance() {
        Map<Bytes, String> map = new HashMap<>();
        map.put(Bytes.utf8("name"), "mini-redis");

        assertThat(map.get(Bytes.utf8("name"))).isEqualTo("mini-redis");
    }

    @Test
    void compareToOrdersByBytes() {
        assertThat(Bytes.utf8("abc")).isLessThan(Bytes.utf8("abd"));
        assertThat(Bytes.utf8("ab")).isLessThan(Bytes.utf8("abc"));
    }

    @Test
    void compareToIsUnsigned() {
        Bytes low = Bytes.of(new byte[] {0x01});
        Bytes high = Bytes.of(new byte[] {(byte) 0xFF});

        assertThat(low).isLessThan(high);
    }

    @Test
    void toLowerAsciiChangesOnlyAsciiLetters() {
        Bytes original = Bytes.utf8("GeT[");
        Bytes lower = original.toLowerAscii();

        assertThat(lower).isEqualTo(Bytes.utf8("get["));
        assertThat(original).isEqualTo(Bytes.utf8("GeT["));
    }

    @Test
    void toLowerAsciiKeepsNonAsciiBytes() {
        Bytes b = Bytes.of(new byte[] {(byte) 0xC3, 'A'});

        assertThat(b.toLowerAscii()).isEqualTo(Bytes.of(new byte[] {(byte) 0xC3, 'a'}));
    }

    @Test
    void toStringEscapesNonPrintableBytesAndBackslash() {
        Bytes b = Bytes.of(new byte[] {'a', 0, '\\', (byte) 0xFF, '\n'});

        assertThat(b.toString()).isEqualTo("a\\x00\\\\\\xff\\x0a");
    }

    @Test
    void decodeWithIso88591IsLossless() {
        Bytes b = Bytes.of(new byte[] {(byte) 0xFF, 0});

        assertThat(b.decode(StandardCharsets.ISO_8859_1)).isEqualTo("ÿ\u0000");
    }
}
