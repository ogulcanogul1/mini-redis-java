# Benchmarks

Every number on this page comes from a recorded run under [`bench/results/`](../bench/results/):
raw JMH output, a summary, and the machine and commit it ran on (`env.json`).

## How we measure

- **Micro benchmarks** use [JMH](https://github.com/openjdk/jmh): several forked JVMs, warmup iterations before
  measurement, and a 99.9% confidence interval for every score.
- **A claim needs a significant difference:** the confidence intervals of the two results must not overlap.
  Otherwise we report "no measurable difference".
- **The hypothesis is written before the run** (in each run's `README.md`) and is not changed afterwards. Results
  that contradict it are reported too.
- **Same conditions:** same machine, fixed heap (`-Xms2g -Xmx2g`), committed code only (`git_dirty: false`).
  Benchmarks never run in CI: shared runners are too noisy.
- Reproduce any run with `bash bench/run.sh <name> <benchmark regex>` (see [`bench/run.sh`](../bench/run.sh)).

## Highlights

| What | Before | After | Factor | Conditions | Run |
|---|---|---|---|---|---|

## All experiments

| Date | Experiment | Question | Verdict |
|---|---|---|---|
