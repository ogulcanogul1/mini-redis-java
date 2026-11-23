package io.github.ogulcanogul1.miniredis.ds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SkipListTest {

    @Test
    void insertDeleteRankAndRangeMaintainOrder() {
        SkipList sl = new SkipList();
        assertThat(sl.isEmpty()).isTrue();
        assertThat(sl.insert(Bytes.ascii("b"), 2.0)).isTrue();
        assertThat(sl.insert(Bytes.ascii("a"), 2.0)).isTrue();
        assertThat(sl.insert(Bytes.ascii("c"), 1.0)).isTrue();
        assertThat(sl.insert(Bytes.ascii("c"), 1.0)).isFalse();
        assertThat(sl.size()).isEqualTo(3);

        // Order: (1.0, c), (2.0, a), (2.0, b)
        assertThat(sl.rank(Bytes.ascii("c"), 1.0)).isZero();
        assertThat(sl.rank(Bytes.ascii("a"), 2.0)).isEqualTo(1L);
        assertThat(sl.rank(Bytes.ascii("b"), 2.0)).isEqualTo(2L);
        assertThat(sl.rank(Bytes.ascii("missing"), 2.0)).isEqualTo(-1L);

        assertThat(sl.rangeByIndex(0, -1))
                .extracting(e -> new String(e.member().toByteArray(), StandardCharsets.UTF_8))
                .containsExactly("c", "a", "b");
        assertThat(sl.rangeByScore(1.5, 2.5))
                .extracting(e -> new String(e.member().toByteArray(), StandardCharsets.UTF_8))
                .containsExactly("a", "b");

        assertThat(sl.delete(Bytes.ascii("a"), 2.0)).isTrue();
        assertThat(sl.delete(Bytes.ascii("a"), 2.0)).isFalse();
        assertThat(sl.size()).isEqualTo(2);
        assertThat(sl.rank(Bytes.ascii("b"), 2.0)).isEqualTo(1L);
    }
}
