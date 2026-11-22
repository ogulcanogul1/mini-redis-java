package io.github.ogulcanogul1.miniredis.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour every {@link Keyspace} must have. Each implementation extends this class. */
abstract class KeyspaceContractTest {

    protected abstract Keyspace newKeyspace();

    @Test
    void emptyKeyspaceHasNoKeys() {
        Keyspace keyspace = newKeyspace();

        assertThat(keyspace.size()).isZero();
        assertThat(keyspace.get(Bytes.utf8("a"))).isEmpty();
        assertThat(keyspace.exists(Bytes.utf8("a"))).isFalse();
    }

    @Test
    void setThenGet() {
        Keyspace keyspace = newKeyspace();

        keyspace.set(Bytes.utf8("k"), Bytes.utf8("v"));

        assertThat(keyspace.get(Bytes.utf8("k"))).contains(Bytes.utf8("v"));
        assertThat(keyspace.exists(Bytes.utf8("k"))).isTrue();
        assertThat(keyspace.size()).isEqualTo(1);
    }

    @Test
    void setReplacesOldValue() {
        Keyspace keyspace = newKeyspace();

        keyspace.set(Bytes.utf8("k"), Bytes.utf8("v1"));
        keyspace.set(Bytes.utf8("k"), Bytes.utf8("v2"));

        assertThat(keyspace.get(Bytes.utf8("k"))).contains(Bytes.utf8("v2"));
        assertThat(keyspace.size()).isEqualTo(1);
    }

    @Test
    void keyWithEqualContentFromAnotherInstanceFindsValue() {
        Keyspace keyspace = newKeyspace();
        keyspace.set(Bytes.utf8("k"), Bytes.utf8("v"));

        assertThat(keyspace.get(Bytes.of(new byte[] {'k'}))).contains(Bytes.utf8("v"));
    }

    @Test
    void deleteReturnsTrueOnlyWhenKeyExisted() {
        Keyspace keyspace = newKeyspace();
        keyspace.set(Bytes.utf8("k"), Bytes.utf8("v"));

        assertThat(keyspace.delete(Bytes.utf8("k"))).isTrue();
        assertThat(keyspace.delete(Bytes.utf8("k"))).isFalse();
        assertThat(keyspace.size()).isZero();
    }

    @Test
    void flushRemovesAllKeysAndKeyspaceStaysUsable() {
        Keyspace keyspace = newKeyspace();
        keyspace.set(Bytes.utf8("a"), Bytes.utf8("1"));
        keyspace.set(Bytes.utf8("b"), Bytes.utf8("2"));

        keyspace.flush();

        assertThat(keyspace.size()).isZero();
        assertThat(keyspace.get(Bytes.utf8("a"))).isEmpty();

        keyspace.set(Bytes.utf8("c"), Bytes.utf8("3"));
        assertThat(keyspace.get(Bytes.utf8("c"))).contains(Bytes.utf8("3"));
    }

    @Test
    void forEachKeyOnEmptyKeyspaceCallsNothing() {
        List<Bytes> seen = new ArrayList<>();

        newKeyspace().forEachKey(seen::add);

        assertThat(seen).isEmpty();
    }

    @Test
    void forEachKeyVisitsEveryKeyOnce() {
        Keyspace keyspace = newKeyspace();
        keyspace.set(Bytes.utf8("a"), Bytes.utf8("1"));
        keyspace.set(Bytes.utf8("b"), Bytes.utf8("2"));
        keyspace.set(Bytes.utf8("c"), Bytes.utf8("3"));
        List<Bytes> seen = new ArrayList<>();

        keyspace.forEachKey(seen::add);

        assertThat(seen).containsExactlyInAnyOrder(Bytes.utf8("a"), Bytes.utf8("b"), Bytes.utf8("c"));
    }

    @Test
    void holdsTenThousandKeys() {
        Keyspace keyspace = newKeyspace();

        for (int i = 0; i < 10_000; i++) {
            keyspace.set(Bytes.utf8("key:" + i), Bytes.utf8("v" + i));
        }

        assertThat(keyspace.size()).isEqualTo(10_000);
        assertThat(keyspace.get(Bytes.utf8("key:9999"))).contains(Bytes.utf8("v9999"));
        assertThat(keyspace.delete(Bytes.utf8("key:0"))).isTrue();
        assertThat(keyspace.size()).isEqualTo(9_999);
    }

    @Test
    void nullArgumentsAreRejected() {
        Keyspace keyspace = newKeyspace();
        Bytes key = Bytes.utf8("k");
        Bytes value = Bytes.utf8("v");

        assertThatThrownBy(() -> keyspace.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keyspace.set(null, value)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keyspace.set(key, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keyspace.delete(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keyspace.exists(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> keyspace.forEachKey(null)).isInstanceOf(NullPointerException.class);
    }
}
