package io.github.ogulcanogul1.miniredis.bench;

import io.github.ogulcanogul1.miniredis.ds.Bytes;
import io.github.ogulcanogul1.miniredis.ds.Dict;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Hypothesis: filling our Dict from empty (every resize included) costs about the same per put as
 * HashMap, within about 2x. Both start small and double.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class DictPutBenchmark {

    private static final int KEYS = 1024;

    private Bytes[] keys;

    @Setup
    public void setUp() {
        keys = new Bytes[KEYS];
        for (int i = 0; i < KEYS; i++) {
            keys[i] = Bytes.ascii("key:" + i);
        }
    }

    /** Fills an empty table with all keys: includes every resize. Reported per put. */
    @Benchmark
    @OperationsPerInvocation(KEYS)
    public Dict<Bytes, Bytes> fillDict() {
        Dict<Bytes, Bytes> d = new Dict<>();
        for (int i = 0; i < KEYS; i++) {
            d.put(keys[i], keys[i]);
        }
        return d;
    }

    @Benchmark
    @OperationsPerInvocation(KEYS)
    public Map<Bytes, Bytes> fillHashMap() {
        Map<Bytes, Bytes> m = new HashMap<>();
        for (int i = 0; i < KEYS; i++) {
            m.put(keys[i], keys[i]);
        }
        return m;
    }
}
