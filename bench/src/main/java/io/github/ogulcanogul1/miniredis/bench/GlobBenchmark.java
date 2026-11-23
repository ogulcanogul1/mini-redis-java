package io.github.ogulcanogul1.miniredis.bench;

import io.github.ogulcanogul1.miniredis.util.Glob;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Hypothesis: on a pattern with many stars that does not match, the naive recursive matcher grows
 * like n^k while ours grows like n * pattern length. On an ordinary pattern both are about the
 * same, so the fix costs nothing in the common case.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class GlobBenchmark {

    /** Length of the text. The worst case pattern has 5 stars. */
    @Param({"10", "20", "40"})
    public int n;

    private byte[] worstPattern;
    private byte[] worstText;
    private byte[] typicalPattern;
    private byte[] typicalText;

    @Setup
    public void setUp() {
        worstPattern = "a*a*a*a*a*b".getBytes(StandardCharsets.US_ASCII);
        worstText = "a".repeat(n).getBytes(StandardCharsets.US_ASCII);
        typicalPattern = "user:*".getBytes(StandardCharsets.US_ASCII);
        typicalText = ("user:" + "1".repeat(n)).getBytes(StandardCharsets.US_ASCII);
    }

    @Benchmark
    public boolean worstCaseNaive() {
        return NaiveGlob.matches(worstPattern, worstText);
    }

    @Benchmark
    public boolean worstCaseOurs() {
        return Glob.matches(worstPattern, worstText);
    }

    @Benchmark
    public boolean typicalNaive() {
        return NaiveGlob.matches(typicalPattern, typicalText);
    }

    @Benchmark
    public boolean typicalOurs() {
        return Glob.matches(typicalPattern, typicalText);
    }
}
