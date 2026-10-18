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
review board → Markdown report; field-level 400s; 404s; a run that outlives its timeout ends
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

## Known gaps
* No browser end-to-end tests (Playwright) yet — planned for phase 9.
* Accessibility is designed in (semantic tables, labelled controls, focus rings, text alternatives
  for chart data) but has not been audited with assistive technology.
* Load on the API itself is not tested; FORGE is a single-user local tool.
