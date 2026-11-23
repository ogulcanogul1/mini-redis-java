# <Experiment name>

Copy this file to `bench/results/<run folder>/README.md` and fill it in.
Write **Question** and **Hypothesis** *before* the run. Do not edit them afterwards.

- **Run:** `<run folder>` (commit and machine: `env.json` in this folder)
- **Question:** What do we want to know?
- **Hypothesis:** What do we expect, and why? Give a number or a direction ("B is at least 2x faster than A for n >= 20").
- **Setup:** benchmark classes and methods, parameters, JMH settings (forks, warmup, measurement), JVM flags. Anything unusual about the machine (on battery, other programs running).
- **Result:** paste `summary.md` and the output of `python bench/report.py compare <this folder> <baseline> <candidate>`.
- **Verdict:** Did the hypothesis hold? Only differences marked "difference is significant" count.
- **Explanation:** Why did we get this result? Point to the code or the concept.
- **Caveats:** What this experiment does *not* show (one machine, a micro benchmark is not end-to-end, JIT effects, ...).
- **Headline** (only if the verdict supports it): one line for `docs/BENCHMARKS.md`, in the form
  `<what> : <before> -> <after> (<factor>x), <condition>, JMH <forks>x<iterations>, 99.9% CI`.
