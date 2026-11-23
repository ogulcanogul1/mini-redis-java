package io.github.ogulcanogul1.miniredis.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplyTest {

    @Test
    void replyIsSealedAndEverySubtypeIsARecord() {
        assertThat(Reply.class.isSealed()).isTrue();

        Class<?>[] subtypes = Reply.class.getPermittedSubclasses();

        assertThat(subtypes).hasSize(6);
        for (Class<?> subtype : subtypes) {
            assertThat(subtype.isRecord()).as(subtype.getSimpleName()).isTrue();
        }
    }

    @Test
    void constantsHaveTheExpectedValues() {
        assertThat(Reply.OK).isEqualTo(new Reply.SimpleString("OK"));
        assertThat(Reply.PONG).isEqualTo(new Reply.SimpleString("PONG"));
        assertThat(Reply.NIL).isEqualTo(new Reply.NullBulk());
    }

    @Test
    void factoriesReturnTheMatchingRecord() {
        assertThat(Reply.simple("OK")).isEqualTo(new Reply.SimpleString("OK"));
        assertThat(Reply.error("ERR syntax error")).isEqualTo(new Reply.ErrorReply("ERR syntax error"));
        assertThat(Reply.integer(42)).isEqualTo(new Reply.IntegerReply(42));
        assertThat(Reply.bulk(Bytes.utf8("x"))).isEqualTo(new Reply.BulkString(Bytes.utf8("x")));
        assertThat(Reply.array(List.of(Reply.OK))).isEqualTo(new Reply.ArrayReply(List.of(Reply.OK)));
    }

    @Test
    void recordsWithEqualContentAreEqual() {
        assertThat(Reply.bulk(Bytes.utf8("x"))).isEqualTo(Reply.bulk(Bytes.utf8("x")));
        assertThat(Reply.bulk(Bytes.utf8("x"))).isNotEqualTo(Reply.bulk(Bytes.utf8("y")));
    }

    @Test
    void arrayCopiesItsList() {
        List<Reply> source = new ArrayList<>();
        source.add(Reply.OK);
        Reply.ArrayReply array = new Reply.ArrayReply(source);

        source.add(Reply.PONG);

        assertThat(array.elements()).containsExactly(Reply.OK);
    }

    @Test
    void arrayRejectsNullElement() {
        assertThatThrownBy(() -> Reply.array(Arrays.asList(Reply.OK, null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> Reply.simple(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Reply.error(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Reply.bulk(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Reply.array(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void simpleAndErrorRepliesRejectLineBreaks() {
        assertThatThrownBy(() -> Reply.simple("a\rb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reply.simple("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reply.error("ERR a\r\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Reply.simple("a\tb")).isEqualTo(new Reply.SimpleString("a\tb"));
    }
}
