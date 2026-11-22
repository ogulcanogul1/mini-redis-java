package io.github.ogulcanogul1.miniredis.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {

    @ParameterizedTest
    @CsvSource({
        "h?llo, hello, true",
        "h?llo, hallo, true",
        "h?llo, hllo, false",
        "h*llo, hello, true",
        "h*llo, heeello, true",
        "h*llo, hell, false",
        "h[ae]llo, hello, true",
        "h[ae]llo, hillo, false",
        "h[^e]llo, hallo, true",
        "h[^e]llo, hello, false",
        "h[a-b]llo, hbllo, true",
        "h[a-b]llo, hcllo, false",
        "h[z-a]llo, hmllo, true",
        "'*', '', true",
        "'*', anything, true",
        "'', '', true",
        "'', a, false",
        "a, '', false",
        "\\*, '*', true",
        "\\*, a, false",
        "a[b, ab, true",
        "a\\, 'a\\', true",
        "*a*b*, xxaxxbxx, true",
        "*a*b*, xxbxxax, false"
    })
    void matchesPatternsFromTheRedisDocs(String pattern, String text, boolean expected) {
        assertThat(matches(pattern, text)).isEqualTo(expected);
    }

    @Test
    void starMatchesNulBytesInText() {
        byte[] text = {'a', 0, 'b'};

        assertThat(Glob.matches(bytes("*"), text)).isTrue();
        assertThat(Glob.matches(bytes("a?b"), text)).isTrue();
    }

    @Test
    void rangeComparesBytesUnsigned() {
        byte[] pattern = {'[', (byte) 0x80, '-', (byte) 0xFF, ']'};

        assertThat(Glob.matches(pattern, new byte[] {(byte) 0x90})).isTrue();
        assertThat(Glob.matches(pattern, new byte[] {0x7F})).isFalse();
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> Glob.matches(null, new byte[0])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Glob.matches(new byte[0], null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @Timeout(2)
    void manyStarsDoNotTakeExponentialTime() {
        byte[] text = "a".repeat(10_000).getBytes(StandardCharsets.US_ASCII);

        assertThat(Glob.matches(bytes("a*a*a*a*a*a*a*a*a*a*b"), text)).isFalse();
    }

    private static boolean matches(String pattern, String text) {
        return Glob.matches(bytes(pattern), bytes(text));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
