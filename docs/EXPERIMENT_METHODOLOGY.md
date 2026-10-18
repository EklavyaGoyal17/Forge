# Experiment Methodology

FORGE is only useful if its conclusions are trustworthy. This document states how experiments are
structured, how measurements are taken, how comparisons are computed, and — just as importantly —
what the numbers **cannot** tell you.

## 1. The workflow

```
Hypothesis → Configuration → Execution → Measurement → Analysis → Improvement → Comparison
```

| Step | What FORGE records | Why |
|---|---|---|
| Hypothesis | Free text, required before the first run | Prevents post-hoc rationalisation ("HARKing"). |
| Configuration | Scenario id + version, variant, every parameter, seed, trial count | A run must be re-executable from the record alone. |
| Execution | Child JVM flags, start/end timestamps, exit code, lifecycle events | Distinguishes "slow" from "crashed" from "timed out". |
| Measurement | Per-trial metrics, latency histograms where relevant, invariant checks | Raw data, not just averages. |
| Analysis | Summary statistics, automatically generated factual observations | Observations are phrased as facts about the data, never as root causes. |
| Comparison | Bootstrap CI, Mann–Whitney U, environment diff, parameter diff | Tells you whether a difference is distinguishable from noise *and* whether the runs were comparable at all. |

## 2. Trials, warm-up, and why a fresh JVM per run

* A **run** is one child JVM execution of one variant. A run contains `trials` repetitions plus
  `warmupTrials` that are executed but discarded.
* Warm-up exists because HotSpot interprets code first, then compiles hot methods with C1 and later
  C2. Early trials measure the interpreter and the compiler, not your algorithm.
* A fresh JVM per run means two variants never share JIT profiles, inlining decisions, heap
  layout, or GC state. (Inside one JVM, benchmarking variant B after variant A can make B look
  faster *or slower* purely because of profile pollution.)
* Microbenchmarks (Algorithm Lab) use **JMH**, which adds its own forks, warm-up iterations,
  blackholes against dead-code elimination, and per-iteration timing. FORGE reports JMH's raw
  per-iteration scores, not only its summary.

## 3. Statistics used

All implemented in `forge-lab/src/main/java/dev/forge/lab/stats` and unit tested.

### Per-run summary
For each metric over trials: `n`, mean, standard deviation (sample, n−1), min, max, median, p90,
p95, p99 (linear interpolation between closest ranks), and coefficient of variation (CV =
stddev/mean). CV is shown because a CV above ~10 % for a timing metric is a sign of a noisy
environment or an unstable workload, and comparisons will need more trials.

### Baseline vs candidate
* **Ratio of medians** `candidate / baseline` with a **seeded percentile bootstrap 95 % CI**
  (10 000 resamples, each run resampled independently). Medians are used because timing data is
  right-skewed and outlier-prone (GC pauses, OS preemption); a single 200 ms pause can move a mean
  substantially but barely moves a median.
* **Mann–Whitney U** (two-sided, normal approximation with tie correction). Non-parametric:
  it does not assume normality. Reported as a secondary signal.
* **Verdict rules**:
  * CI entirely on the "better" side of 1.0 → *improvement*.
  * CI entirely on the "worse" side → *regression*.
  * CI contains 1.0 → *no difference detected at this sample size*. This is **not** evidence that
    the variants are equivalent.
  * `n < 5` in either run → verdict is still computed but flagged *insufficient sample*.
* **Comparability checks** before any verdict is shown: different scenario, different non-variant
  parameters, different JVM version, different available processor count, or different max heap
  produce warnings, because the comparison would then confound the variant with the environment.

### What we deliberately do not do
* No p-value thresholds presented as "significant/not significant" in isolation.
* No averaging of percentiles across runs (percentiles do not compose).
* No extrapolation of latency to load levels that were not measured.

## 4. Known sources of noise on developer machines

* **Hybrid CPUs** (e.g. Intel 12th-gen P-cores + E-cores): the OS may move a benchmark thread
  between core types mid-run. Expect higher CV than on a server.
* **Frequency scaling / turbo / thermal throttling** on laptops, especially on battery.
* **Background processes** — browsers, IDE indexing, antivirus scanning freshly written jars.
* **GC** — collection pauses appear as tail latency. FORGE records the GC algorithm in the
  environment so that you can notice when two runs used different collectors.
* **Timer resolution** — `System.nanoTime()` is precise but not free (~20–30 ns); operations
  shorter than a few hundred nanoseconds must be batched (JMH does this).

## 5. Concurrency experiments are different

* A concurrency bug that does **not** appear in N stress trials has *not* been shown to be absent.
  FORGE reports "violations observed in k of N trials", never "thread-safe".
* The violation rate of a racy variant depends on core count, contention and JIT state. For
  example, HotSpot's C2 compiler may hoist a plain field load/store out of a loop, which turns a
  fine-grained race into a coarse one and *increases* lost updates. That is a real effect, not a
  FORGE artefact — and a good reason not to reason about racy code by "imagining" interleavings.
* Explorer mode is exhaustive **only** over the instrumented yield points and **only** under
  sequential consistency. It can prove that *at that granularity* no schedule violates the
  invariant, which is a much stronger statement than stress testing, but still not a proof about
  the JVM.

## 6. Generalisation caveats (shown next to results)

A result holds for: this machine, this JVM, this heap size, this data size and distribution, this
concurrency level, and this code version. Before acting on it, ask:

1. Would the ranking change at 10× the data size? (cache effects, algorithmic crossover points)
2. Would it change under a different input distribution? (sorted vs random, hit vs miss)
3. Is the measured operation actually on the critical path of the real system?
4. Did the optimisation change correctness (invariant checks), memory footprint, or tail latency?
