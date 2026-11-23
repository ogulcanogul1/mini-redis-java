"""Records benchmark environments and summarizes JMH results (K13). Standard library only.

  python bench/report.py env <dir> --java <java> --jvm-args "<args>" --jmh-args "<args>"
  python bench/report.py summarize <dir>
  python bench/report.py compare <dir> <baseline benchmark> <candidate benchmark>

`compare` prints, for every parameter combination, baseline -> candidate, the ratio and whether
the 99.9% confidence intervals overlap. Only a non-overlapping pair supports a claim.
"""

import argparse
import csv
import datetime
import json
import os
import platform
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
    args = p.parse_args()
    {"env": cmd_env, "summarize": cmd_summarize, "compare": cmd_compare}[args.cmd](args)


if __name__ == "__main__":
    main()
