package io.github.ogulcanogul1.miniredis.bench;

/**
 * Baseline for {@link GlobBenchmark}: the straightforward recursive matcher. Every {@code *} tries
 * every possible split of the rest of the text, so k stars against a non-matching text of length n
 * take about n^k steps. Supports only {@code *}, {@code ?} and literals, enough for the benchmark.
 */
final class NaiveGlob {

    private NaiveGlob() {}

    static boolean matches(byte[] pattern, byte[] text) {
        return match(pattern, 0, text, 0);
    }

    private static boolean match(byte[] pattern, int p, byte[] text, int t) {
        if (p == pattern.length) {
            return t == text.length;
        }
        if (pattern[p] == '*') {
            for (int skip = t; skip <= text.length; skip++) {
                if (match(pattern, p + 1, text, skip)) {
                    return true;
                }
            }
            return false;
        }
        if (t < text.length && (pattern[p] == '?' || pattern[p] == text[t])) {
            return match(pattern, p + 1, text, t + 1);
        }
        return false;
    }
}
