"""Records benchmark environments, summarizes/compares JMH results, and plots charts (K13).

  python bench/report.py env <dir> --java <java> --jvm-args "<args>" --jmh-args "<args>"
  python bench/report.py summarize <dir>
  python bench/report.py compare <dir> <baseline benchmark> <candidate benchmark>
  python bench/report.py plot <dir> [--out plot.png]

`compare` prints, for every parameter combination, baseline -> candidate, the ratio and whether
the 99.9% confidence intervals overlap. Only a non-overlapping pair supports a claim. `plot` uses
matplotlib (optional for `env`/`summarize`/`compare`).
"""

import argparse
import csv
import datetime
import json
import math
import os
import platform
import statistics
import subprocess
import sys
from pathlib import Path


def run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        return (r.stdout + r.stderr).strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def cpu_name():
    if sys.platform == "win32":
        return run(["powershell.exe", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name"])
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor()


def ram_bytes():
    if sys.platform == "win32":
        out = run(["powershell.exe", "-NoProfile", "-Command",
                   "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"])
        return int(out) if out.isdigit() else None
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (ValueError, OSError, AttributeError):
        return None


def power_plan():
    if sys.platform == "win32":
        return run(["powercfg", "/getactivescheme"])
    return ""


def java_props(java):
    out = run([java, "-XshowSettings:properties", "-version"])
    props = {}
    for line in out.splitlines():
        line = line.strip()
        for key in ("java.version", "java.vendor", "java.vm.name", "java.vm.version"):
            if line.startswith(key + " = "):
                props[key] = line.split(" = ", 1)[1]
    return props


def git(*args):
    try:
        r = subprocess.run(["git", *args], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def cmd_env(args):
    out = Path(args.dir)
    status = git("status", "--porcelain", "--", ".", ":(exclude)bench/results")
    env = {
        "timestamp": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "git_commit": git("rev-parse", "HEAD"),
        "git_dirty": status is None or bool(status),  # not a git repo counts as dirty
        "os": platform.platform(),
        "cpu": cpu_name(),
        "logical_cpus": os.cpu_count(),
        "ram_bytes": ram_bytes(),
        "power_plan": power_plan(),
        "java": java_props(args.java),
        "jvm_args": args.jvm_args,
        "jmh_args": args.jmh_args,
    }
    (out / "env.json").write_text(json.dumps(env, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if env["git_dirty"]:
        print("WARNING: uncommitted changes. This run must not be used as a result (K13).", file=sys.stderr)


def load(dir_):
    data = json.loads((Path(dir_) / "jmh.json").read_text(encoding="utf-8"))
    rows = []
    for b in data:
        m = b["primaryMetric"]
        # With a single iteration JMH writes "NaN" for the error and the interval.
        lo, hi = (float(x) for x in m["scoreConfidence"])
        rows.append({
            "benchmark": b["benchmark"].rsplit(".", 2)[-2] + "." + b["benchmark"].rsplit(".", 1)[-1],
            "params": ";".join(f"{k}={v}" for k, v in sorted(b.get("params", {}).items())),
            "mode": b["mode"],
            "threads": b["threads"],
            "forks": b["forks"],
            "iterations": b["measurementIterations"],
            "score": float(m["score"]),
            "error_99_9": float(m["scoreError"]),
            "ci_low": lo,
            "ci_high": hi,
            "unit": m["scoreUnit"],
        })
    return rows


def fmt(x):
    return f"{x:.4g}"


def cmd_summarize(args):
    rows = load(args.dir)
    out = Path(args.dir)
    with (out / "summary.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    lines = ["| Benchmark | Params | Score | +/- (99.9%) | Unit |", "|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['benchmark']} | {r['params']} | {fmt(r['score'])} | {fmt(r['error_99_9'])} | {r['unit']} |")
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def cmd_compare(args):
    rows = load(args.dir)
    by = {}
    for r in rows:
        by.setdefault(r["params"], {})[r["benchmark"].split(".")[-1]] = r
    found = False
    for params, group in sorted(by.items()):
        a, b = group.get(args.baseline), group.get(args.candidate)
        if not a or not b:
            continue
        found = True
        known = all(x == x for x in (a["ci_low"], a["ci_high"], b["ci_low"], b["ci_high"]))  # NaN != NaN
        overlap = not known or (a["ci_low"] <= b["ci_high"] and b["ci_low"] <= a["ci_high"])
        ratio = a["score"] / b["score"] if b["score"] else float("inf")
        if not known:
            verdict = "no claim: no confidence interval (too few iterations)"
        elif overlap:
            verdict = "no claim: confidence intervals overlap"
        else:
            verdict = "difference is significant"
        print(f"[{params or 'no params'}] {args.baseline} {fmt(a['score'])} {a['unit']} -> "
              f"{args.candidate} {fmt(b['score'])} {b['unit']} | baseline/candidate = {fmt(ratio)}x | {verdict}")
    if not found:
        print("no matching benchmarks", file=sys.stderr)
        sys.exit(1)


def _read_e2e_rps(dir_, prefix):
    by_test = {}
    for path in sorted(Path(dir_).glob(f"{prefix}-run-*.csv")):
        with path.open(encoding="utf-8-sig") as f:
            for row in csv.DictReader(f):
                by_test.setdefault(row["test"], []).append(float(row["rps"]))
    return by_test


def cmd_plot(args):
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    out_dir = Path(args.dir)
    out_file = Path(args.out) if Path(args.out).is_absolute() else out_dir / args.out
    summary_csv = out_dir / "summary.csv"

    fig, ax = plt.subplots(figsize=(9, 4.5))
    if summary_csv.is_file():
        with summary_csv.open(encoding="utf-8-sig") as f:
            rows = list(csv.DictReader(f))
        if not rows:
            print("empty summary.csv", file=sys.stderr)
            sys.exit(1)
        labels = [f"{r['benchmark']} ({r['params']})" if r["params"] else r["benchmark"] for r in rows]
        scores = [float(r["score"]) for r in rows]
        errs = [0.0 if math.isnan(float(r["error_99_9"])) else float(r["error_99_9"]) for r in rows]
        y = list(range(len(labels)))
        ax.barh(y, scores, xerr=errs, capsize=3)
        ax.set_yticks(y)
        ax.set_yticklabels(labels)
        ax.invert_yaxis()
        ax.set_xlabel(rows[0]["unit"])
        ax.set_title(out_dir.name)
    else:
        ours = _read_e2e_rps(out_dir, "ours")
        ref = _read_e2e_rps(out_dir, "redis")
        tests = list(ours.keys())
        if not tests or not ref:
            print(f"no summary.csv or E2E CSVs in {out_dir}", file=sys.stderr)
            sys.exit(1)
        x = list(range(len(tests)))
        w = 0.35
        our_meds = [statistics.median(ours[t]) for t in tests]
        ref_meds = [statistics.median(ref[t]) for t in tests]
        ax.bar([i - w / 2 for i in x], our_meds, width=w, label="mini-redis")
        ax.bar([i + w / 2 for i in x], ref_meds, width=w, label="redis-7.0.15")
        ax.set_xticks(x)
        ax.set_xticklabels(tests)
        ax.set_ylabel("Requests / sec (median)")
        ax.set_title(out_dir.name)
        ax.legend()

    fig.tight_layout()
    fig.savefig(out_file, dpi=150)
    plt.close(fig)
    print(f"wrote {out_file}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("env")
    e.add_argument("dir")
    e.add_argument("--java", default="java")
    e.add_argument("--jvm-args", default="")
    e.add_argument("--jmh-args", default="")
    s = sub.add_parser("summarize")
    s.add_argument("dir")
    c = sub.add_parser("compare")
    c.add_argument("dir")
    c.add_argument("baseline")
    c.add_argument("candidate")
    pl = sub.add_parser("plot")
    pl.add_argument("dir")
    pl.add_argument("--out", default="plot.png")
    args = p.parse_args()
    {"env": cmd_env, "summarize": cmd_summarize, "compare": cmd_compare, "plot": cmd_plot}[args.cmd](args)


if __name__ == "__main__":
    main()
