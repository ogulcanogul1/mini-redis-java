package io.github.ogulcanogul1.miniredis.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumbersTest {

    @Test
    void parsesValidLongsIncludingBoundaries() {
        assertThat(parse("0")).hasValue(0);
        assertThat(parse("-1")).hasValue(-1);
        assertThat(parse("42")).hasValue(42);
        assertThat(parse(Long.toString(Long.MAX_VALUE))).hasValue(Long.MAX_VALUE);
        assertThat(parse(Long.toString(Long.MIN_VALUE))).hasValue(Long.MIN_VALUE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "-",
                "+1",
                "01",
                "00",
                "-0",
                "-01",
                " 1",
                "1 ",
                "1a",
                "9223372036854775808",
                "-9223372036854775809",
                "99999999999999999999"
            })
    void rejectsInvalidInputs(String text) {
        assertThat(parse(text)).isEmpty();
    }

    @Test
    void rejectsNonAsciiBytes() {
        byte[] bytes = {(byte) 0x80, '1'};
        assertThat(Numbers.parseLong(bytes, 0, bytes.length)).isEmpty();
    }

    @Test
    void parsesOnlyTheGivenRange() {
        byte[] bytes = "xx-123yy".getBytes(StandardCharsets.US_ASCII);
        assertThat(Numbers.parseLong(bytes, 2, 6)).hasValue(-123);
    }

    @Test
    void rejectsNullAndOutOfBoundsRanges() {
        byte[] bytes = "12".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> Numbers.parseLong(null, 0, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Numbers.parseLong(bytes, -1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> Numbers.parseLong(bytes, 0, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> Numbers.parseLong(bytes, 2, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    private static java.util.OptionalLong parse(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        return Numbers.parseLong(bytes, 0, bytes.length);
    }
}
