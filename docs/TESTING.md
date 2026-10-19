# Testing

FORGE's tests are organised by what could go wrong, not by layer. Everything below runs in CI
(`.github/workflows/ci.yml`).

## How to run

```bash
mvn verify                     # forge-lab + forge-server (server integration tests need Docker; skipped without it)
cd forge-ui && npm test        # Vitest + Testing Library
cd forge-ui && npm run typecheck
```

## What is tested and why

### Statistics (`forge-lab/.../stats/StatsTest`)
* Percentiles against NumPy's linear-interpolation definition on a known sample.
* Sample standard deviation against a closed-form value.
* Normal CDF at standard quantiles (1.96, −2.5758).
* Mann–Whitney U and p-value against a hand computation (U = 2, z = −2.2822, p ≈ 0.0225).
* Bootstrap determinism for a fixed seed, and that the interval covers the true ratio.
* Comparison verdicts: improvement, regression, no difference, identical constants, zero baseline,
  small-sample warnings.

### Interleaving explorer (`forge-lab/.../concurrency/InterleavingExplorerTest`)
Deterministic tests with exact expected counts derived by hand:
* Two racy increments → exactly 6 schedules, exactly 4 lose an update (C(4,2) orderings, 2 serial).
* Two lock-protected increments → exactly 2 schedules (one per critical-section order), 0 violations.
* Opposite lock order → deadlock detected with the exact wait-for cycle.
* Random sampling is reproducible for a seed; the step bound stops non-terminating schedules.

### Scenarios (`forge-lab/.../scenarios/*Test`)
* Every *correct* variant holds its invariant under real stress (a deterministic property of correct code).
* Every *broken* variant is shown broken through **explorer mode only** (deterministic). Broken
  variants under stress are timing-dependent; asserting on them would create flaky tests, so the
  suite does not.
* Deadlock-free variants complete all transfers and conserve money; nested locks deadlock in the explorer.
* Volatile/atomic stop flags are observed.
* Backpressure: blocking, shedding and locked buffers stay within capacity and lose nothing under a
  4× overload; blocking drops nothing while shedding drops work; the check-then-act buffer overfills
  in the explorer (exhaustive) while the locked one never does; variants built on uninstrumented
  `java.util.concurrent` queues are rejected in explorer mode.
* JMH integration: the lookup benchmark's four variants agree on the hit count for the same data,
  and a real (tiny) JMH run yields one trial per measurement iteration.
* Catalog: every variant's displayed source resolves from the packaged source and contains the
  real implementation; descriptor defaults validate; the validator rejects out-of-range, unknown
  and wrongly-typed input.

### Performance measurement (`forge-lab/.../performance/*Test`, `scenarios/PerformanceScenarioTest`)
* The open-model generator runs on a **virtual clock** against a fake one-connection server, so
  every latency is exact. A 1 s server stall at 100 req/s gives p99 = 1 ms measured from the actual
  send time but 910 ms from the intended send time (derived by hand in the test), and exactly 111
  requests ≥ 10 ms.
* Warm-up requests are sent but not measured; a response slower than the timeout is a timeout
  recorded at the timeout; requests that cannot get a connection before their deadline are
  timeouts that were never sent; per-window series counts.
* The arrival schedule does not drift for non-integer intervals (3 req/s lands exactly on 1 s; an
  hour at 300 req/s lands exactly on the hour).
* HdrHistogram summaries against known data within the histogram's precision; percentiles a
  sample cannot resolve are flagged.
* The scenario test makes short real runs of all four server designs and asserts only structure
  and accounting (scheduled/measured counts, every measured request accounted for, loopback bind
  address, worker counts) — never latency or throughput values, which depend on the machine.
* Parameter bounds keep the load small; a run whose trials cannot finish before the deadline is refused.

### Regression gate (`forge-server/.../analysis/RegressionGateTest`)
Comparisons built from fixed samples: a worse primary metric whose interval excludes 1.0 is a
regression; better is an improvement; overlapping samples are "no difference detected"; different
parameters, environment or scenario version yield NOT_COMPARABLE; a newly violated invariant is a
regression even when the metric improved; secondary regressions are listed but do not gate.

### Runner protocol (`forge-lab/.../runner/RunnerProcessTest`)
Launches `RunnerMain` as a real child process: a valid spec produces a result with environment
and JVM flags; invalid parameters, unknown scenarios and non-JSON input exit with code 2 without
running anything.

### Isolation (`forge-server/.../run/RunnerLauncherTest`)
Uses deliberately misbehaving "runners" (`MisbehavingRunners`):
* **Hang + grandchild** → the run is `TIMED_OUT` within the limit and the grandchild process is dead.
* **Cancellation** kills promptly.
* **stdout flood** trips the output cap.
* **Environment probe** sees only `Path/SystemRoot/TEMP/TMP` (Windows) or `PATH/TMPDIR/LANG`
  (Unix) and a private temp dir, which is deleted afterwards.
* `@argfile` quoting of backslashes and quotes.

### End-to-end (`forge-server/.../ExperimentFlowIntegrationTest`, Testcontainers PostgreSQL 17)
HTTP API → Flyway schema → DB-backed queue → isolated runner JVM → stored results → comparison →
review board → Markdown report; pinned-baseline regression gate (pin/unpin, a parameter change is
NOT_COMPARABLE, report section, deleting an experiment whose run is pinned); field-level 400s; 404s; a run that outlives its timeout ends
`TIMED_OUT`; a running run can be cancelled.

### Packaged UI serving (`forge-server/.../UiConfigTest`)
Hashed `/assets/*` files are served from the assets directory, client-side routes (`/`,
`/experiments/<id>`) forward to `index.html`, and `/api/*` is never rewritten. (Added after a real
bug: the first container build served `index.html` but 404'd every asset.)

### Frontend (`forge-ui/src/test`)
* Formatting of numbers, units, durations and relative time.
* Lost-update conflict detection used to highlight trace rows (including failed vs successful CAS).
* Trace rendering of a deadlock with its wait-for cycle.
* Run configuration form: variants disabled outside their supported modes, mode-specific parameters, client-side bounds mirroring the server,
  variant source display, submission payload, explore mode forcing a single trial.

### Frontend: regression panel (`forge-ui/src/test/regression.test.tsx`)
Nothing renders until a baseline is pinned; each checked run shows its gate, ratio, interval,
reasons and non-gating secondary regressions; a run with different parameters shows "not
comparable" even when its raw comparison is directional.

### Frontend: HTTP load renderer (`forge-ui/src/test/httpLoad.test.tsx`)
* Per-trial client percentile table plus the merged-histogram row; error counts; p99.9 hidden when
  a trial has fewer than 1 000 values; values at the request timeout marked as lower bounds.
* The percentile spectrum joins series by percentile and drops unresolvable points; per-window
  counts become rates; virtual threads are labelled as having no pool.

### Recorded end-to-end check (Performance Lab, 2026-10-19)
Through the HTTP API on a development server with its own database: baseline `blocking-pool` and
candidate `async` at default parameters (5 trials + 1 warm-up), then `virtual-threads` and another
`blocking-pool` run. All succeeded in the isolated runner; the comparison reported an improvement
in the primary metric (client p99) with a bootstrap interval excluding 1.0, and the run detail
rendered its tables and charts from the stored result (checked in a browser through the DOM).

## Known gaps
* No browser end-to-end tests (Playwright) yet — planned for phase 9.
* Accessibility is designed in (semantic tables, labelled controls, focus rings, text alternatives
  for chart data) but has not been audited with assistive technology.
* Load on the API itself is not tested; FORGE is a single-user local tool.
