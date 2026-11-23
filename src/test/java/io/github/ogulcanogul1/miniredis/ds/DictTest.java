package io.github.ogulcanogul1.miniredis.ds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class DictTest {

    @Test
    void putGetAndRemove() {
        Dict<String, String> dict = new Dict<>();
        assertThat(dict.get("a")).isNull();
        assertThat(dict.put("a", "1")).isNull();
        assertThat(dict.get("a")).isEqualTo("1");
        assertThat(dict.put("a", "2")).isEqualTo("1");
        assertThat(dict.size()).isEqualTo(1);
        assertThat(dict.remove("a")).isEqualTo("2");
        assertThat(dict.remove("a")).isNull();
        assertThat(dict.isEmpty()).isTrue();
    }

    @Test
    void nullKeyOrValueIsRejected() {
        Dict<String, String> dict = new Dict<>();
        assertThatThrownBy(() -> dict.put(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dict.put("k", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dict.get(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void collidingKeysAreAllKept() {
        Dict<Integer, Integer> dict = new Dict<>(k -> 42);
        for (int i = 0; i < 1000; i++) {
            dict.put(i, i * 2);
        }
        for (int i = 0; i < 1000; i++) {
            assertThat(dict.get(i)).isEqualTo(i * 2);
        }
        for (int i = 0; i < 1000; i += 2) {
            assertThat(dict.remove(i)).isEqualTo(i * 2);
        }
        for (int i = 0; i < 1000; i++) {
            assertThat(dict.containsKey(i)).isEqualTo(i % 2 == 1);
        }
    }

    @Test
    void capacityDoublesWhenSizeExceedsIt() {
        Dict<Integer, Integer> dict = new Dict<>();
        assertThat(dict.capacity()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            dict.put(i, i);
        }
        assertThat(dict.capacity()).isEqualTo(4);
        dict.put(4, 4);
        assertThat(dict.capacity()).isEqualTo(8);
        for (int i = 5; i < 9; i++) {
            dict.put(i, i);
        }
        assertThat(dict.capacity()).isEqualTo(16);
        for (int i = 0; i < 9; i++) {
            assertThat(dict.get(i)).isEqualTo(i);
        }
    }

    @Test
    void clearResetsCapacityAndSize() {
        Dict<Integer, Integer> dict = new Dict<>();
        for (int i = 0; i < 20; i++) {
            dict.put(i, i);
        }
        dict.clear();
        assertThat(dict.capacity()).isEqualTo(4);
        assertThat(dict.size()).isZero();
    }

    @Test
    void standardHasherMixesHighBits() {
        var hasher = Hasher.<Integer>standard();
        java.util.Set<Integer> lowBits = new java.util.HashSet<>();
        for (int i = 0; i < 16; i++) {
            lowBits.add(hasher.hash(i << 16) & 0xF);
        }
        assertThat(lowBits).hasSize(16);
    }

    @Test
    void oneMillionKeys() {
        Dict<Integer, Integer> dict = new Dict<>();
        for (int i = 0; i < 1_000_000; i++) {
            dict.put(i, i);
        }
        assertThat(dict.size()).isEqualTo(1_000_000);
        for (int i = 0; i < 1_000_000; i++) {
            assertThat(dict.get(i)).isEqualTo(i);
        }
        for (int i = 0; i < 1_000_000; i += 2) {
            dict.remove(i);
        }
        assertThat(dict.size()).isEqualTo(500_000);
    }

    @Test
    void iteratorVisitsEveryEntryOnce() {
        Dict<Integer, Integer> dict = new Dict<>();
        for (int i = 0; i < 50; i++) {
            dict.put(i, i);
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Dict.Entry<Integer, Integer> e : dict) {
            assertThat(seen.add(e.key())).isTrue();
        }
        assertThat(seen).hasSize(50);
    }

    @Test
    void emptyIteratorHasNoNext() {
        Iterator<Dict.Entry<String, String>> it = new Dict<String, String>().iterator();
        assertThat(it.hasNext()).isFalse();
        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void iteratorRemoveDeletesCurrentEntry() {
        Dict<Integer, Integer> dict = new Dict<>();
        for (int i = 0; i < 10; i++) {
            dict.put(i, i);
        }
        Iterator<Dict.Entry<Integer, Integer>> it = dict.iterator();
        while (it.hasNext()) {
            if (it.next().key() % 2 == 0) {
                it.remove();
            }
        }
        assertThat(dict.size()).isEqualTo(5);
        assertThat(dict.containsKey(0)).isFalse();
        assertThat(dict.containsKey(1)).isTrue();
    }

    @Test
    void removeTwiceWithoutNextThrows() {
        Dict<Integer, Integer> dict = new Dict<>();
        dict.put(1, 1);
        Iterator<Dict.Entry<Integer, Integer>> it = dict.iterator();
        it.next();
        it.remove();
        assertThatThrownBy(it::remove).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void putDuringIterationThrows() {
        Dict<Integer, Integer> dict = new Dict<>();
        dict.put(1, 1);
        dict.put(2, 2);
        Iterator<Dict.Entry<Integer, Integer>> it = dict.iterator();
        it.next();
        dict.put(3, 3);
        assertThatThrownBy(it::next).isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    void getDuringIterationIsAllowed() {
        Dict<Integer, Integer> dict = new Dict<>();
        dict.put(1, 1);
        dict.put(2, 2);
        Iterator<Dict.Entry<Integer, Integer>> it = dict.iterator();
        it.next();
        dict.get(2);
        dict.containsKey(2);
        assertThat(it.hasNext()).isTrue();
        assertThat(it.next()).isNotNull();
    }
}
