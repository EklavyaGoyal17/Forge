# Testing

FORGE's tests are organised by what could go wrong, not by layer. Everything below runs in CI
(`.github/workflows/ci.yml`).

## How to run

End to end, against a running stack (`docker compose up -d --build --wait`):

```bash
python3 scripts/e2e_smoke.py http://127.0.0.1:8090
```

Unit and integration tests:

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

### Liveness explorer (`forge-lab/.../concurrency/LivenessExplorerTest`)
Programs whose state graphs can be drawn by hand:
* A loop `x=1; progress; x=0` → exactly 2 states, 2 edges, no livelock.
* A thread spinning without progress → 1 state, a 1-step livelock cycle, starvation of that thread.
* A thread spinning until another thread writes a flag → the spin cycle is *unfair* (the writer is
  enabled but never scheduled) and is correctly not reported.
* Two threads on one non-fair lock → starvation of each (barging), no livelock, no deadlock.
* Safety is checked in every reachable state (exactly one state has both threads inside).
* A program that is not a function of its schedule is reported as an error, not judged.

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
* Livelock/starvation (`LivenessScenariosTest`): verdicts asserted from complete state graphs only —
  polite flags livelock with mutual exclusion intact; Peterson livelock- and starvation-free;
  readers-preference starves the writer, writer-preference the readers, the CAS-loop ticket lock a
  thread; the fetch-and-add ticket lock nobody. Stress assertions are limited to safety, progress of
  correct variants, and the ticket lock serialising readers.
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

### Failure Lab (`forge-lab/.../scenarios/failure/FailureLabTest`)
* Circuit breaker on a fake clock: opens at the failure rate once `minCalls` are recorded, fails
  fast during the cool-down, admits at most `probes` half-open calls, closes after successful probes,
  reopens (and restarts the cool-down) on a failed probe, and lets old failures slide out of the
  window.
* The dependency against a real loopback server: added latency is applied, error share 1.0 answers
  500, a dropped connection is an `IOException`, a restart refuses connections on the old port and
  serves on a new one. (The test allows the dropped request to arrive twice because the JDK
  `HttpClient` may resend it — found by this test.)
* Seeded error selection is a function of seed and sequence and has the configured share.
* Whole runs: a control run without a fault succeeds 100% with ≈ 1 attempt per request; immediate
  retries multiply attempts under a total error fault; the breaker opens and short-circuits; the
  retry budget caps retries and refuses some; plans that could exhaust local ports or whose fault
  outlives the sustained phase are rejected. Recovery times and latencies are not asserted.

### AI Investigator (`forge-server/.../investigator/InvestigatorUnitTest`, `InvestigatorIntegrationTest`)
* Answer checking: unknown citations are reported; a number equal to evidence, equal after rounding
  to the answer's precision, or within 0.5% is supported; a derived ratio and an invented number are
  both flagged; small whole numbers and numbers inside citations are not checked.
* The no-provider answer says it is retrieval, includes related findings and evidence, and leaves out
  unrelated ones; citations of evidence that does not exist are dropped from findings.
* The local provider speaks OpenAI-compatible chat completions (path, roles, content, usage) against a
  stub; non-loopback local URLs and an Anthropic provider without a key are rejected at start-up.
* End to end on PostgreSQL 17: two real runs → evidence bundle with the expected ids → rule-based
  findings whose citations all resolve → a question answered through the **official Anthropic SDK
  pointed at a local stub** of the Messages API (no key, no network, no cost) → the request carries the
  model, `x-api-key`, effort `high`, the refusal-fallback beta header and `fallbacks: "default"`, the
  system prompt and the evidence → the stored answer lists the stub's invented number and unknown
  citation. The real Anthropic API is not called by any test.

### Challenge Arena (`forge-server/.../challenge/ChallengeCriteriaTest`, `InvestigatorIntegrationTest.challengeAttemptLifecycle`)
* Criteria on synthetic recorded runs, step by step, with the exact expected PASS / NOT_YET sequence:
  the race (explorer reproduction; a fix explored with only 1 deposit per thread does not count; stress
  with < 20 trials or of a never-explored variant does not count), the misleading benchmark (different
  hit rates are not comparable; a slower scan is a valid decisive finding; an indistinguishable pair is
  not decisive), the slow API (an interval that reaches the 5× threshold is not enough), the unstable
  dependency (a lighter load than the storm run does not count).
* Every challenge names a real scenario, a valid starting variant and parameters, hints, review
  questions and at least two criteria.
* Through the API on PostgreSQL: hypothesis required, 404 for unknown challenges, the attempt's
  experiment is created and tagged, the challenge's own starting run (a real explorer run) passes the
  first criterion with its evidence, hints are capped at the number available, answers are validated
  and stored, the list shows progress.

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
* Liveness verdicts and witnesses: livelock cycle with one column per thread, prefix toggle,
  "holds" only for complete graphs, exploration errors shown instead of verdicts.
* Failure timeline: breaker-open ranges, per-second rates, success / recovery / sustained-phase
  attempts per request, trial switching, "no recovery" and control-run states.
* Investigator panel: findings grouped by kind, the off-host warning before asking, the answer check
  (unknown citations struck through, unverified numbers listed), opening cited evidence, asking a
  question and showing the stored rule-based answer.
* Challenge Arena: progress on the list, hypothesis required to start, criteria with evidence, hint
  counting, queueing the starting run, saving review answers, completion banner.
* Accessibility (`a11y.test.tsx`, `contrast.test.ts`): axe-core WCAG 2.1 A/AA rules on the shell, run
  form, liveness, failure, investigator and challenge views (a planted-violation test proves detection);
  4.5:1 contrast for every text token on every surface in both themes and for button text on the accent.
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
* End-to-end coverage of the deployed image is at the API level (`scripts/e2e_smoke.py` in CI); there
  is no browser automation (Playwright). UI behaviour is covered by component tests.
* Accessibility is checked automatically (axe-core on jsdom, token contrast) but has not been audited
  with a screen reader; layout-dependent rules (rendered contrast over images, reflow) are not covered
  by jsdom.
* The real Anthropic API is never called by tests (it needs a key and is billed); the SDK path is
  tested against a local stub of the Messages API.
* Load on the API itself is not tested; FORGE is a single-user local tool.
