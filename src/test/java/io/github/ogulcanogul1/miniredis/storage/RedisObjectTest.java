package io.github.ogulcanogul1.miniredis.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import org.junit.jupiter.api.Test;

class RedisObjectTest {

    @Test
    void stringObjectStoresValueTypeAndTouchesLru() {
        Bytes payload = Bytes.ascii("hello");
        RedisObject obj = RedisObject.string(payload, 1_000L);
        assertThat(obj.type()).isEqualTo(RedisObject.Type.STRING);
        assertThat(obj.asString()).isEqualTo(payload);
        assertThat(obj.lru()).isEqualTo(1_000L);

        obj.touch(2_500L);
        assertThat(obj.lru()).isEqualTo(2_500L);
    }
}
