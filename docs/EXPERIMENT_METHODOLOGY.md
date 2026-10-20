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

### Regression gate (pinned baseline)
An experiment can pin one succeeded run as its baseline; every other succeeded run is checked
against it with the comparison above. The gate is conservative:
* **REGRESSION** — the runs are comparable and the primary metric's 95 % interval lies entirely on
  the worse side of 1.0, *or* the baseline held its invariant and the run violated it (a correctness
  regression always counts, whatever the timing says).
* **NOT_COMPARABLE** — parameters, scenario version or environment differ, or no primary-metric
  value is shared. No verdict is given, because any difference would be confounded.
* Otherwise the comparison's own verdict (improvement, no difference detected, identical, not
  directional). Regressions in secondary metrics are listed but do not gate.

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
* Liveness (livelock, starvation) cannot be decided by running something "for a while". FORGE's
  liveness mode searches the complete state graph for a **weakly fair** cycle without progress
  (ADR-012). A found cycle is a concrete schedule that can repeat forever; "no starvation" is only
  claimed when the graph is complete. Stress mode for these scenarios reports wait times, which say
  how likely starvation was on this machine, not whether it is possible.

## 6. Load tests (Performance Lab)

* **Open model.** Requests are due on a fixed schedule (constant arrival rate), whether or not
  earlier ones have completed — the way independent users arrive. A closed model (N users who
  each wait for a response) automatically slows down when the server does, so it cannot show
  what overload does to the users who keep arriving.
* **Latency from the intended send time.** When the generator cannot send on time (all
  connections busy, a stalled thread), the delay is part of the latency a user would have seen.
  FORGE records `completed − intended` as *the* client latency and `completed − sent` next to it,
  so the size of coordinated omission is visible rather than silently removed.
* **Phases.** Every trial applies the load for a warm-up phase (not measured) and then a
  sustained phase; requests whose intended send time falls in the sustained phase are measured.
  Warm-up trials (whole trials discarded) are also available, as for every scenario.
* **Percentiles.** Per-trial p50/p90/p95/p99/max are trial metrics, compared across runs by their
  median like any other metric. The run aggregate merges the raw HdrHistograms. A percentile
  needs at least `1/(1−p)` values to be distinguishable from the maximum (p99.9 needs 1 000);
  unresolvable percentiles are not shown.
* **Timeouts** are recorded when the client gives up, so a percentile at the timeout is a lower
  bound; FORGE says so in the observations and marks such values with `≥`.
* **Server-side breakdown.** Queue wait and service time are measured by the server. Means (not
  percentiles) of client latency, queue wait and service time add up; the remainder is time
  outside the server's measurement (generator lag, loopback I/O, client callbacks).
* **Shared machine.** Client and server share the JVM, CPU and garbage collector, and both rely on
  OS timers (coarse on Windows). Absolute numbers over loopback are lower than over a network;
  the shape — where throughput plateaus and how latency grows past it — is the result.

## 7. Database experiments (Database Studio)

* **Same data for compared runs.** The dataset is generated from the `dataSeed` parameter (default
  1), not from the per-run seed, and the accounts queried in each trial come from it too — so two
  variants are measured on identical rows with identical lookups (a paired design). A different
  `dataSeed` is a different dataset and is flagged as a parameter difference.
* **Client time is primary.** Latency is measured by the client around execute + fetch, including
  the round trip. EXPLAIN (ANALYZE, BUFFERS) is captured alongside for the plan, buffers and the
  server's own execution time — which includes per-node timing overhead, so it is never used as
  the headline number.
* **Warm caches.** The seeded data fits in memory; "buffers read" are usually the OS page cache,
  not disk. Results do not describe tables larger than RAM.
* **Fresh statistics.** Tables are VACUUM ANALYZEd after loading, so plans reflect current
  statistics and a current visibility map — the best case for index-only scans.
* **Plans are choices, not laws.** The captured plan is what PostgreSQL chose for this data size,
  these settings (recorded with the run) and this version. Descriptions say what an index *makes
  possible*; the plan shows what was actually done.
* **Correctness first.** Pagination, N+1 and lost-update scenarios carry invariants (identical
  pages, identical loaded graphs, no lost deposits) checked in every trial.

## 8. JVM experiments (JVM Lab)

* **Sampled vs exact.** JFR's CPU and allocation views are statistical: an execution sample every
  10 ms per thread running Java code, at most 300 allocation samples per second. They answer
  *where*; totals come from exact counters recorded next to them — HotSpot's per-thread allocated
  bytes (`ThreadMXBean.getCurrentThreadAllocatedBytes`) and the JVM's BLOCKED/WAITING counts
  (thread contention monitoring). The run shows both, so their agreement can be checked (a recorded
  boxed-stream run: 1,927 MB sampled vs 1,999 MB exact; `String +=`: 1,265 MB vs 2,762 MB, because
  its bursts exceed the sampling rate).
* **Allocation sample weights.** A sample's weight is the bytes its thread allocated since its
  previous sample. Each thread's first sample in a recording is discarded (its weight reaches back to
  before the recording), and a weight that accumulated while the sampler was rate-limited lands on
  the next sample, which can be in different code.
* **Thresholds make lower bounds.** Only lock waits at or above the threshold (0.1 ms in the
  contention scenario) become JFR events; spinning that succeeds is invisible to every counter.
* **The measured window.** Tables count events between the first and last JFR marker (the measured
  trials), so JFR's own start-up and dump code is not attributed to the scenario.
* **Warm-up is per method.** Every warm-up trial loads a fresh copy of the kernel class, so its
  methods start interpreted with no profile. The JDK code it calls and the compiler threads are
  shared with earlier trials. Compilation events are placed on the invocation axis through markers
  carrying `System.nanoTime()`; "code available from invocation *k*" means the first invocation that
  *started* after the compilation finished. On-stack replacement (OSR) code is used only by a loop
  that is already running, so a later invocation can still enter the method interpreted.
* **JIT-mode flags change the whole JVM**, including JFR's own code and the summariser, and the
  compile thresholds differ between JDK versions — compare modes on one JDK only (the version is
  part of the environment comparison).

## 9. Generalisation caveats (shown next to results)

A result holds for: this machine, this JVM, this heap size, this data size and distribution, this
concurrency level, and this code version. Before acting on it, ask:

1. Would the ranking change at 10× the data size? (cache effects, algorithmic crossover points)
2. Would it change under a different input distribution? (sorted vs random, hit vs miss)
3. Is the measured operation actually on the critical path of the real system?
4. Did the optimisation change correctness (invariant checks), memory footprint, or tail latency?
