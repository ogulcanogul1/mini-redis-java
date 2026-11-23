#!/usr/bin/env bash
# Runs an end-to-end redis-benchmark comparison in Docker (K13).
# Both mini-redis and real Redis 7.0.15 run inside containers with identical CPU/memory limits.
#
# Usage, from the repo root (Git Bash):
#   bash bench/e2e.sh <name> [clients] [requests] [runs]
#   bash bench/e2e.sh single 1 20000 5
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: bash bench/e2e.sh <name> [clients=1] [requests=20000] [runs=5]" >&2
    exit 2
fi

NAME="$1"
CLIENTS="${2:-1}"
REQUESTS="${3:-20000}"
RUNS="${4:-5}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/bench/results/$(date +%Y-%m-%d-%H%M)-$NAME"
if [ -e "$OUT" ]; then
    echo "already exists: $OUT" >&2
    exit 2
fi
mkdir -p "$OUT"

JAVA="java"
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi

NET="mini-redis-bench-$$"
OUR_CTR="mini-redis-ours-$$"
REF_CTR="mini-redis-ref-$$"
CPUS="2"
MEMORY="2g"

cleanup() {
    docker rm -f "$OUR_CTR" "$REF_CTR" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

python "$ROOT/bench/report.py" env "$OUT" --java "$JAVA" \
    --jvm-args "-Xms1g -Xmx1g (Docker --cpus=$CPUS --memory=$MEMORY)" \
    --jmh-args "redis-benchmark -c $CLIENTS -n $REQUESTS -t ping,set,get --csv (runs=$RUNS)"

docker build -t mini-redis:bench "$ROOT"
docker network create "$NET" >/dev/null

run_suite() {
    local label="$1"
    local host="$2"
    local port="$3"

    # Warmup (not recorded)
    docker run --rm --network "$NET" redis:7.0.15 \
        redis-benchmark -h "$host" -p "$port" -c "$CLIENTS" -n 2000 -t ping,set,get -q >/dev/null

    for i in $(seq 1 "$RUNS"); do
        docker run --rm --network "$NET" redis:7.0.15 \
            redis-benchmark -h "$host" -p "$port" -c "$CLIENTS" -n "$REQUESTS" -t ping,set,get --csv \
            > "$OUT/${label}-run-${i}.csv"
    done
}

# 1. mini-redis
docker run -d --name "$OUR_CTR" --network "$NET" --cpus="$CPUS" --memory="$MEMORY" \
    mini-redis:bench --bind 0.0.0.0 --port 6380 --loglevel warning >/dev/null
sleep 2
run_suite "ours" "$OUR_CTR" 6380
docker rm -f "$OUR_CTR" >/dev/null

# 2. Redis 7.0.15
docker run -d --name "$REF_CTR" --network "$NET" --cpus="$CPUS" --memory="$MEMORY" \
    redis:7.0.15 redis-server --save '' --appendonly no >/dev/null
sleep 1
run_suite "redis" "$REF_CTR" 6379
docker rm -f "$REF_CTR" >/dev/null

python - "$OUT" "$RUNS" <<'PY'
import csv, statistics, sys
from pathlib import Path

out = Path(sys.argv[1])
runs = int(sys.argv[2])

def summarize(prefix):
    by_test = {}
    for i in range(1, runs + 1):
        with (out / f"{prefix}-run-{i}.csv").open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                t = row["test"]
                by_test.setdefault(t, {"rps": [], "p50": [], "p99": []})
                by_test[t]["rps"].append(float(row["rps"]))
                by_test[t]["p50"].append(float(row["p50_latency_ms"]))
                by_test[t]["p99"].append(float(row["p99_latency_ms"]))
    return by_test

ours = summarize("ours")
ref = summarize("redis")

lines = [
    "| Test | Server | Median RPS (min-max) | Median p50 (ms) | Median p99 (ms) |",
    "|---|---|---|---|---|",
]
for t in ours:
    for label, data in (("mini-redis", ours[t]), ("redis-7.0.15", ref[t])):
        rps_med = statistics.median(data["rps"])
        p50_med = statistics.median(data["p50"])
        p99_med = statistics.median(data["p99"])
        lines.append(
            f"| {t} | {label} | {rps_med:.1f} ({min(data['rps']):.1f}-{max(data['rps']):.1f}) "
            f"| {p50_med:.3f} | {p99_med:.3f} |"
        )

summary = "\n".join(lines) + "\n"
(out / "summary.md").write_text(summary, encoding="utf-8")
print(summary)
PY

echo "results: $OUT"
