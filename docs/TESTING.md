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

### JFR summary (`forge-lab/.../jvm/JfrSummariserTest`)
Records a real JFR file in the test JVM around workloads whose JFR footprint is certain, then
asserts structure only: an explicit `System.gc()` appears with cause `System.gc()` and heap
before/after; a monitor held across a 150 ms sleep while another thread waits for it is recorded
as a `JavaMonitorEnter` on that class, attributed to the waiting test code; the same with a
`ReentrantLock` appears as `ThreadPark`; allocation samples attribute `byte[]` to the allocating
method and the allocating thread's first sample is discarded; a spinning method appears among the
CPU samples; markers come back in order with their `nanoTime`; tables are restricted to the window
between the markers; summarising one file twice gives equal summaries. Pure helpers: marker-based
clock conversion (interpolation and extrapolation) and JVM array descriptors (`[B` → `byte[]`).

### JVM Lab scenarios (`forge-lab/.../scenarios/JvmScenariosTest`, `.../scenarios/jvm/JvmScenarioLogicTest`)
* Warm-up: every invocation of every trial is timed and positive; checksums equal the reference;
  compilations of the kernel belong to that trial's fresh class loader and are placed on a valid
  invocation index; the JIT-mode variants refuse to run in a JVM without their flag.
* Warm-up metrics from synthetic series: invocation-axis mapping, steady state, warm-up cost, the
  steady-state point (unaffected by a late slow stretch), a flat series needing no warm-up.
* Allocation: all six implementations return the reference result (also on empty input and
  small-value-cache edge cases); boxed stream allocates more than the primitive loop and
  `String +=` more than 10× the presized builder (measured cold, before C2 could scalar-replace
  anything — a property of the code, not of timing).
* Lock contention: every variant keeps count and checksum; any JFR-recorded contention attributed to
  scenario code is on the accumulator the variant uses (this caught measurement-induced contention:
  workers reading their own `ThreadMXBean` counters at the same moment blocked each other inside
  the JDK); the raw recording stays in the work directory.

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
* Per-variant JVM flags: a JIT-mode variant adds its declared flag before `-cp`, other variants add
  none, flags outside the allow-list (`-XX:+UnlockDiagnosticVMOptions`, `-javaagent:…`) are refused,
  and every flag declared anywhere in the catalog is on the allow-list.

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

### Lab database isolation (`forge-server/.../lab/LabDatabaseServiceTest`, Testcontainers PostgreSQL 17)
The restricted role can create and drop schemas in its own database; it cannot connect to the
platform database, cannot create databases or roles, and has its statement/lock timeouts. After a
simulated restart the old password no longer works and leftover `run_*` schemas are swept, while
other schemas are untouched. JDBC URL rewriting and run-id → schema-name mapping are covered.

### Database Studio scenarios (`forge-lab/.../scenarios/DatabaseScenariosTest`, Testcontainers PostgreSQL 17)
Only deterministic properties are asserted: the access path per index variant (Seq Scan without
an index, the named index otherwise, Index Only Scan for the covering index); identical probe
checksums across variants with different run seeds but the same `dataSeed`; OFFSET page 1,000 of
20 reads at least the 19,980 skipped rows while keyset reads fewer than 100, and both return the
same pages; N+1 / join / batched issue 31 / 1 / 2 statements and load identical graphs; the four
correct SQL deposit patterns lose nothing; per-run schemas are gone afterwards; scenarios refuse to
run without a lab database. Whether the racy read-modify-write loses deposits is timing-dependent
and only its accounting is asserted.

### Frontend: Database Studio (`forge-ui/src/test/database.test.tsx`)
Plan flattening with per-node self time (multiplied by loops) and estimate error; ≥ 10×
misestimates flagged; the view shows PostgreSQL settings, the exact SQL, a plan selector and the
depth-sweep values; "no secondary index" is stated explicitly.

### Frontend: regression panel (`forge-ui/src/test/regression.test.tsx`)
Nothing renders until a baseline is pinned; each checked run shows its gate, ratio, interval,
reasons and non-gating secondary regressions; a run with different parameters shows "not
comparable" even when its raw comparison is directional.

### Frontend: HTTP load renderer (`forge-ui/src/test/httpLoad.test.tsx`)
* Per-trial client percentile table plus the merged-histogram row; error counts; p99.9 hidden when
  a trial has fewer than 1 000 values; values at the request timeout marked as lower bounds.
* The percentile spectrum joins series by percentile and drops unresolvable points; per-window
  counts become rates; virtual threads are labelled as having no pool.

### Frontend: JVM Lab renderer (`forge-ui/src/test/jvm.test.tsx`)
Frame shortening (packages dropped, arrays kept), tier names, rolling median, start/end marker
pairing; the view lists each kernel compilation with the invocation its code was available from
(or "after the last invocation"), self and inclusive hot-method tables with frame types, allocation
by class and site next to the exact counter and the discarded first samples, the GC pause
timeline and table, lock tables separating scenario waits from JDK/JFR-internal ones with the
threshold, and the interpreter-only case (no compiler, no compilations, no GC).

### Recorded end-to-end check (Performance Lab, 2026-10-19)
Through the HTTP API on a development server with its own database: baseline `blocking-pool` and
candidate `async` at default parameters (5 trials + 1 warm-up), then `virtual-threads` and another
`blocking-pool` run. All succeeded in the isolated runner; the comparison reported an improvement
in the primary metric (client p99) with a bootstrap interval excluding 1.0, and the run detail
rendered its tables and charts from the stored result (checked in a browser through the DOM).

### Recorded end-to-end check (JVM Lab, 2026-10-19)
Through the HTTP API on a development server with its own database (`forge_phase2_e2e`, port 8091,
lab database disabled so the restricted role of the server on 8090 was not rotated) and the shaded
runner jar: all four warm-up modes, all six allocation implementations and all three contention
variants at default parameters. All 13 runs succeeded with their invariants held; the JIT-mode runs
recorded `-XX:TieredStopAtLevel=1`, `-XX:-TieredCompilation` and `-Xint` in their launch flags;
each JFR recording was about 150 KB; and the run detail rendered the warm-up timeline, compilation
table, hot methods, allocation, GC and lock tables from the stored results (checked in a browser
through the DOM; a screenshot of the warm-up chart was taken on an earlier run of the same build stage). Numbers are in ROADMAP.md, Phase 5.

## Known gaps
* The JVM Lab has not been run in the Docker image (Temurin 21 JRE); JFR is part of that runtime,
  but the run has not been recorded there.
* No browser end-to-end tests (Playwright) yet — planned for phase 9.
* Accessibility is designed in (semantic tables, labelled controls, focus rings, text alternatives
  for chart data) but has not been audited with assistive technology.
* Load on the API itself is not tested; FORGE is a single-user local tool.
