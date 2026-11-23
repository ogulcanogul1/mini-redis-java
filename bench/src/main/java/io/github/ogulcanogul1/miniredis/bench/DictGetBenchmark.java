package io.github.ogulcanogul1.miniredis.bench;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Hypothesis: our Dict is in the same order of magnitude as HashMap (within about 2x) for get
 * with Bytes keys. HashMap has years of tuning, so being slower is expected; being 10x slower
 * would point to a bug (bad hash mixing, missing resize).
 *
 * <p>Keys used for lookup are separate Bytes objects with the same content, like keys that arrive
 * from the network, so the {@code this == o} shortcut in equals does not help.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class DictGetBenchmark {

    /** Number of keys. 1024 fits in the CPU cache, 1M does not. Must be a power of two. */
    @Param({"1024", "1048576"})
    public int size;

    private Bytes[] keys;
    private Bytes[] lookups;
    private Dict<Bytes, Bytes> dict;
    private Map<Bytes, Bytes> hashMap;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        keys = new Bytes[size];
        lookups = new Bytes[size];
        dict = new Dict<>();
        hashMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            keys[i] = Bytes.ascii("key:" + i);
            lookups[i] = Bytes.ascii("key:" + i);
            dict.put(keys[i], keys[i]);
            hashMap.put(keys[i], keys[i]);
        }
        // Visit the keys in a scrambled order, not in insertion order.
        for (int i = size - 1; i > 0; i--) {
            int j = (int) ((i * 2654435761L) % (i + 1));
            Bytes tmp = lookups[i];
            lookups[i] = lookups[j];
            lookups[j] = tmp;
        }
    }

    @Benchmark
    public Bytes getDict() {
        return dict.get(lookups[next++ & (size - 1)]);
    }

    @Benchmark
    public Bytes getHashMap() {
        return hashMap.get(lookups[next++ & (size - 1)]);
    }
}
