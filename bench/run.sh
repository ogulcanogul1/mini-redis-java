#!/usr/bin/env bash
# Runs one JMH experiment and records it under bench/results/<date>-<time>-<name>/ (K13).
#
# Usage, from the repo root (Git Bash):
#   bash bench/run.sh <name> <JMH regex> [extra JMH options]
#   bash bench/run.sh glob GlobBenchmark
#   bash bench/run.sh dict 'Dict(Get|Put)Benchmark'
#   bash bench/run.sh smoke GlobBenchmark -f 1 -wi 1 -i 1 -w 100ms -r 100ms   # quick check, never a result
#
# What it does: installs the main project (tests included, so the measured code is the current,
# green code), builds bench/target/benchmarks.jar, writes env.json, runs JMH with JSON output,
# writes summary.csv and summary.md.
# MVN_ARGS is passed to every mvn call (e.g. MVN_ARGS="-Dmaven.repo.local=/tmp/m2").
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "usage: bash bench/run.sh <name> <JMH regex> [extra JMH options]" >&2
    exit 2
fi
NAME="$1"
REGEX="$2"
shift 2

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MVN_ARGS="${MVN_ARGS:-}"
JAVA="java"
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi
# Fixed heap for every forked benchmark JVM, so GC sizing does not differ between runs.
JVM_ARGS="-Xms2g -Xmx2g"

# shellcheck disable=SC2086
mvn -B -q $MVN_ARGS -f "$ROOT/pom.xml" install
# shellcheck disable=SC2086
mvn -B -q $MVN_ARGS -f "$ROOT/bench/pom.xml" package

OUT="$ROOT/bench/results/$(date +%Y-%m-%d-%H%M)-$NAME"
if [ -e "$OUT" ]; then
    echo "already exists: $OUT" >&2
    exit 2
fi
mkdir -p "$OUT"

python "$ROOT/bench/report.py" env "$OUT" --java "$JAVA" --jvm-args "$JVM_ARGS" --jmh-args "$REGEX $*"

"$JAVA" -jar "$ROOT/bench/target/benchmarks.jar" "$REGEX" -jvmArgsAppend "$JVM_ARGS" \
    -rf json -rff "$OUT/jmh.json" "$@" 2>&1 | tee "$OUT/jmh.log"

python "$ROOT/bench/report.py" summarize "$OUT"
echo "results: $OUT"
