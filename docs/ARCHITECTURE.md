# FORGE Architecture

> Status: living document. Sections marked **(implemented)** describe code that exists and is
> covered by tests; sections marked **(planned)** describe intended design only.

## 1. Goals that drive the architecture

1. **Evidence over decoration.** Every number in the UI must come from a real execution that was
   recorded with enough context to reproduce it.
2. **Isolation of workloads from the platform.** A benchmark that spins, deadlocks, or exhausts its
   heap must not take down the server, corrupt persisted data, or skew other runs.
3. **Reproducibility.** A run is a function of *(scenario version, variant, parameters, seed,
   environment)*. All five are stored with the result.
4. **Simplicity.** One deployable server, one database, one child-process runner. No Kubernetes,
   no message broker, no microservices until a concrete need appears.

## 2. Component overview (implemented)

```
 ┌──────────────────────┐   HTTP/JSON    ┌──────────────────────────────────────────┐
 │ forge-ui             │ ─────────────► │ forge-server (Spring Boot 4, Java 21)    │
 │ React + TS + Vite    │ ◄───────────── │                                          │
 │ Tailwind, Recharts   │   polling      │  ExperimentController / RunController    │
 └──────────────────────┘                │  RunQueue (single worker, bounded queue) │
                                         │  RunnerLauncher ──┐                      │
                                         │  Flyway + JDBC    │                      │
                                         └───────┬───────────┼──────────────────────┘
                                                 │           │ ProcessBuilder
                                                 ▼           ▼ (stdin: RunSpec JSON)
                                         ┌──────────────┐  ┌───────────────────────────┐
                                         │ PostgreSQL   │  │ Child JVM: forge-lab      │
                                         │ experiments, │  │ RunnerMain                │
                                         │ runs, events │  │  - -Xmx / CPU count caps  │
                                         │ artifacts    │  │  - empty environment      │
                                         └──────────────┘  │  - private temp dir       │
                                                           │  - wall-clock timeout     │
                                                           │ stdout: JSON-lines events │
                                                           │         + final result    │
                                                           └───────────────────────────┘
```

### Maven modules

| Module | Responsibility | Depends on |
|---|---|---|
| `forge-lab` | Scenario SPI, built-in scenarios, statistics, runner protocol, `RunnerMain` entry point, open-model HTTP load generator, Database Studio helpers (per-run schema, seeded data, EXPLAIN capture), JVM Lab helpers (JFR capture and summary with `jdk.jfr`). Plain Java — **no Spring**, so it is fast to start in a child JVM and easy to unit test. | Jackson 3, JMH, HdrHistogram, PostgreSQL JDBC |
| `forge-server` | REST API, persistence (PostgreSQL + Flyway), run orchestration, child-JVM launcher, comparison and report generation, Engineering Review Board rubric. | `forge-lab`, Spring Boot |
| `forge-ui` (npm) | Developer workstation UI. Renders only data returned by the API. | — |

## 3. Key decisions (ADR summary)

### ADR-001 — Modular monolith + child-process runner
* **Decision:** The server never executes scenario code in its own JVM. Each run is a fresh child
  JVM started with explicit resource flags.
* **Why:** (a) crash/OOM/deadlock containment, (b) a fresh JVM per run removes JIT and heap state
  carried over from previous runs — a major source of benchmark contamination, (c) hard kill on
  timeout is reliable for processes but not for threads (`Thread.stop` no longer exists; a
  thread stuck in a `synchronized` deadlock or a non-interruptible spin loop cannot be recovered).
* **Cost:** ~150–400 ms JVM start-up per run (not measured inside the timed region).
* **Alternatives rejected:** in-process thread pool (no isolation), container-per-run (requires a
  Docker daemon on every developer machine for even the simplest experiment; kept as the planned
  hardening path for untrusted code — see SECURITY.md).

### ADR-002 — Runs execute serially by default
* **Decision:** `RunQueue` has one worker thread. Additional runs wait in a bounded FIFO queue.
* **Why:** two benchmarks sharing CPU cores measure each other. Serial execution is the single
  most important noise control we can offer for free. Concurrency is configurable
  (`forge.runner.max-concurrent-runs`) but values > 1 are flagged in the recorded environment.

### ADR-003 — Built-in scenarios only (no uploaded code) in early phases
* **Decision:** users choose a scenario, a variant, and bounded parameters. They cannot upload code.
* **Why:** the threat model for executing arbitrary code is much larger than for parameterising
  vetted code. The Scenario SPI makes it possible to add user code later behind a real sandbox.

### ADR-004 — JSON-lines protocol over stdin/stdout
* **Decision:** the server writes a single `RunSpec` JSON document to the child's stdin; the child
  writes newline-delimited JSON messages (`event`, `result`, `error`) to stdout.
* **Why:** trivially debuggable, language-neutral, no ports to allocate or secure. `System.out` is
  re-pointed to stderr inside the child so scenario or library output (e.g. JMH progress) can never
  corrupt the protocol stream. Stdout and stderr are both size-capped by the server.

### ADR-005 — PostgreSQL + Flyway + plain JDBC (`JdbcClient`)
* **Decision:** schema managed by Flyway migrations; data access through Spring's `JdbcClient`.
  Structured results are stored as `jsonb`.
* **Why:** PostgreSQL is also the subject of the Database Studio (Phase 4), so one engine serves
  both purposes. `JdbcClient` keeps SQL visible — appropriate for a tool whose users are learning
  to reason about SQL — and avoids ORM lazy-loading surprises. `jsonb` keeps the result model
  flexible while scenario output formats are still evolving.

### ADR-006 — Two complementary concurrency techniques
* **Stress mode** runs real threads at full speed and reports how often an invariant was violated.
  It is *statistical*: a bug may not manifest in any given run, and the violation rate depends on
  core count, JIT state and OS scheduling.
* **Explorer mode** runs the same logic on real threads under a *controlled scheduler* that only
  lets one thread advance between instrumented yield points. It enumerates every interleaving at
  that granularity (stateless model checking, bounded) and produces a concrete counterexample
  schedule. It is *deterministic* but only as precise as its yield points, and it assumes
  sequential consistency — it does **not** model Java Memory Model reorderings or
  visibility failures (those are demonstrated in stress mode instead).
* **Liveness mode** (livelock and starvation scenarios, ADR-012) builds the explicit state graph of
  threads that loop forever and searches it for fair cycles without progress.
* Neither technique proves the absence of concurrency bugs. Both say so in the UI.

### ADR-007 — JMH for microbenchmarks
* Algorithm Lab microbenchmarks are executed by JMH (forked, with warm-up), never by hand-written
  `System.nanoTime()` loops. The one exception is the deliberately *misleading* naive timer used
  in teaching material, which is labelled as such.

### ADR-008 — Statistics: robust and honest
* Primary comparisons use **medians** and a **seeded percentile bootstrap 95% CI for the ratio of
  medians**, plus a Mann–Whitney U test as a secondary signal. A difference is only called an
  improvement/regression when the CI excludes 1.0; otherwise the verdict is
  *"no difference detected at this sample size"*. Sample sizes under 5 trials produce a warning.
  See `EXPERIMENT_METHODOLOGY.md`.

### ADR-009 — Performance Lab: open model, measured from the intended send time (implemented)
* **Decision:** HTTP load is generated at a constant arrival rate (`OpenLoopGenerator`) against a
  target service the scenario starts itself (`TargetService`, the JDK's `com.sun.net.httpserver`
  bound to 127.0.0.1 on an ephemeral port, inside the runner JVM). Each request's latency is
  `completed − intended send time`; `completed − actual send time` is recorded alongside it.
  Latencies go into HdrHistograms (µs, 3 significant digits); per-trial percentiles are stored as
  trial metrics, and raw histograms are merged (percentiles are never averaged) for the run aggregate.
* **Why:** a closed-model generator (N users each waiting for a response) slows its own arrival
  rate when the server slows down, and measuring from the actual send time silently drops the
  waiting time of the requests hurt most (coordinated omission). Both hide overload. The
  in-process loopback target makes "no external host can be addressed" structural rather than a
  configuration rule.
* **Server-side evidence:** an instrumented executor records queue wait (submitted → started) and
  how long workers were occupied by measured requests; `workers ÷ occupancy` is the Little's-law
  capacity bound shown next to the achieved throughput.
* **Trade-off:** client and server share one JVM, CPU and GC; the generator relies on OS timers
  (its wake-up lag is reported per trial). Both are stated as caveats on every result.
* **Not yet:** a k6 adapter (`grafana/k6`); deferred by design: a `grafana/k6` container would need Docker access from the runner (the runner has an empty environment and no Docker socket — see SECURITY.md) and, on Docker Desktop, a target bound beyond 127.0.0.1 so the container can reach it. Both weaken the isolation model; revisit together with the dedicated runner container in Phase 9.

### ADR-010 — Database Studio: a separate lab database, a restricted role, a schema per run (implemented)
* **Decision:** Database Studio scenarios run against a separate database (`forge_lab`) on the same
  PostgreSQL server, as a dedicated role (`forge_lab_runner`) that `LabDatabaseService` creates or
  updates on first use: login only (no superuser, CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS),
  connection limit, role-level `statement_timeout` / `lock_timeout` /
  `idle_in_transaction_session_timeout`, and a password regenerated on every server start.
  `REVOKE CONNECT ON DATABASE <platform> FROM PUBLIC` keeps the role out of the platform database
  (only when the platform user owns it; the status reports whether isolation is enforced).
* **Credentials** reach the runner only for `Lab.DATABASE` scenarios and only inside the
  `RunSpec` written to its stdin — never on a command line (visible in process lists) or in the
  stored environment, which records the database and role names only.
* **Per-run schema:** each run creates `run_<run id>`, seeds it deterministically from the
  `dataSeed` parameter, measures, and drops it; the server drops it again after the run (in case
  the runner was killed) and sweeps leftovers on startup.
* **Why not the platform database or a container per run:** the platform tables must be
  unreachable from experiment code; a container per run would need Docker access from the server
  and add seconds per run. A separate database + restricted role gives the isolation that matters
  here (no access to platform data, bounded statements) at no extra infrastructure.
* **Why `dataSeed` is a parameter:** runs that are compared must see identical rows. Seeding from
  the per-run seed (the first implementation) silently gave every run different data; making the
  dataset part of the configuration turns a different dataset into a visible parameter difference.

### ADR-011 — JVM Lab: JFR recorded by the scenario, summarised in the runner (implemented)
* **Decision:** JVM Lab scenarios start a `jdk.jfr.Recording` programmatically (`JfrCapture`)
  around their measured trials, write it into the run's private work directory, parse it there with
  `jdk.jfr.consumer.RecordingFile` (`JfrSummariser`) and return only bounded summary tables in
  `ScenarioResult.details.jvm`: hot methods (self, application-inclusive, stack tops with
  interpreted/compiled/inlined frame types), allocation by class and site, GC pauses joined with
  heap before/after, contended monitors and parked locks with stack tops, and compilations of the
  scenario's own code. The raw `.jfr` (≈ 150 KB for the built-in scenarios, hard-capped at 64 MB)
  is deleted with the work directory; nothing larger than the summary crosses the protocol.
* **Why programmatic and not `-XX:StartFlightRecording`:** the recording covers exactly the
  measured trials (not JVM start-up or warm-up trials), each scenario chooses its thresholds (lock
  waits ≥ 0.1 ms for the contention scenario, 1 ms elsewhere), only the events the summary needs are
  enabled (not the `profile` configuration), and the launcher needs no JFR-specific flag.
* **Two clocks:** JFR timestamps and `System.nanoTime()` are different clocks. A custom event
  (`JfrMarker`, `forge.Marker`) carries `nanoTime` at known points, so JFR events can be placed on
  the scenario's own timeline (a compilation finishing → "C2 code available from invocation 379")
  and the tables can be restricted to the window between the first and last marker.
* **Allocation weights:** the first `jdk.ObjectAllocationSample` of each thread in a recording is
  discarded. Its weight is "bytes since the thread's previous sample", and with no previous sample
  in this recording it reached back to before the recording (observed: 41.8 MB credited to an
  allocation-free loop). Exact totals come from HotSpot's per-thread allocation counter instead.
* **JIT-mode variants:** a `VariantSpec` may declare JVM flags. The launcher reads them from the
  compiled descriptor for the run's variant (never from the request) and accepts only
  `-Xint`, `-XX:-TieredCompilation`, `-XX:TieredStopAtLevel=1`. A scenario refuses to run in a JVM
  that lacks its variant's flags, so a unit test or IDE run cannot report numbers for the wrong mode.
* **Cold code per trial:** the warm-up scenario defines a fresh copy of its kernel class in a new
  class loader for every trial, so each trial starts with no compiled code and no profile for the
  measured methods. Shared JDK code stays warm (stated as a caveat).
* **Cost:** JFR adds its own (small) overhead to the measured trials, and parsing takes 50–500 ms
  per run after the measurement (≈ 0.5 s under `-Xint`). Both are outside the timed regions.

### ADR-012 — Liveness: explicit state graph and fair cycles, not a step bound (implemented)
Livelock and starvation are properties of *infinite* executions. The interleaving explorer can only
report "this schedule exceeded the step bound", which cannot distinguish a real livelock from an
unfair scheduler that never ran the thread that would have made progress.

`LivenessExplorer` therefore builds the reachable **state graph** of a small program whose threads
loop forever. A state is the scenario's snapshot of shared memory (plus thread-local values that
matter, which scenarios keep in their state object) + each thread's next action + lock holders.
Each execution replays a known path from the initial state on fresh threads (stateless replay, as
in ADR-006) and extends the graph with untried steps until it reaches a state whose steps are all
known; a FIFO frontier keeps replay paths short. Threads mark progress with `Probe.progress()`.

* **Livelock** = a reachable cycle with no progress by anyone; **starvation of T** = a cycle in which
  T makes no progress. Cycles are found as strongly connected components (iterative Tarjan) of the
  graph restricted to the relevant edges.
* Only **weakly fair** cycles count: every running thread takes a step in the cycle or is blocked
  somewhere in it. A cycle in which an enabled thread never runs is reported as nothing.
* Soundness: every edge of a witness was executed, so a reported cycle is a real schedule even when
  the state budget ran out. "Holds" is claimed only for a complete graph.
* If replaying a known path reaches a different state, the program is not a function of its
  schedule (e.g. it reads a clock or a random source) and the run fails with that error instead of
  a verdict. A unit test covers this.
* Limits: same granularity and sequential-consistency assumptions as ADR-006; the state snapshot
  must capture everything that influences a thread's future (scenarios are written so that it does:
  ticket counters are compared modulo a power of two, and the CAS-based variant keeps its counter
  in range, so abstract states are exact).

Alternative considered: bounded DFS with a step limit and a "looks periodic" heuristic. Rejected —
it cannot separate unfair schedules from livelocks and gives no completeness claim.

## 4. Technology choices

| Choice | Why | Trade-off |
|---|---|---|
| Java 21 bytecode (`--release 21`), any JDK ≥ 21 at runtime | 21 is the LTS baseline the project targets; the development machine runs JDK 25, and the Docker image runs Temurin 21. Recorded per run, so results from different JDKs are flagged as not comparable. | Newer-JDK-only APIs are unavailable. |
| Spring Boot 4.0 (Jackson 3, modular starters) | Current major line with long support ahead; FORGE is meant to last a career. | Fewer examples online than Boot 3. |
| Plain JDBC (`JdbcClient`) over JPA | SQL stays visible and reviewable; no lazy-loading surprises; `jsonb` for evolving result shapes. | Hand-written row mappers. |
| JMH for microbenchmarks | Forking, warm-up, blackholes, per-iteration data. | Each run spawns extra JVMs; minimum run time is seconds. |
| React 19 + TanStack Query + Recharts + cmdk + Tailwind 4 | Small, well-understood pieces; server state is polled, not duplicated in client stores. | Polling (1–1.5 s) instead of push; adequate for a single-user tool. |
| Previous-major frontend toolchain (TypeScript 5.9, Vite 7, React Router 7, Vitest 3) | Pinned to mature releases rather than the newest majors. | Upgrades are deliberate future work. |
| No message broker, no Kubernetes | One user, one machine, one queue table. `SKIP LOCKED` already gives safe multi-worker claiming. | Horizontal scale-out is not a goal. |

## 5. Data model (implemented)

```
experiment 1 ──── * run 1 ──── * run_event
                      1 ──── * run_artifact
```

* `experiment` — title, engineering question, hypothesis, scenario id, notebook fields
  (observations, alternative explanations, decision, follow-ups), status, tags, and an optional
  pinned baseline run (V2) that every other succeeded run is checked against for regressions.
* `run` — variant, parameters (`jsonb`), seed, trials, role (`BASELINE`/`CANDIDATE`/`EXPLORATORY`),
  lifecycle status (`QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT`),
  environment (`jsonb`), result (`jsonb`), exit code, timing.
* `run_event` — ordered lifecycle and progress log, visible in the UI timeline.
* `run_artifact` — raw outputs (runner stderr, JMH log, thread dumps, traces), size-capped.

Database Studio data lives elsewhere: in per-run schemas of the separate `forge_lab` database
(ADR-010), created and dropped by each run; nothing of it is persisted except the run's result.

## 6. Scenario SPI (implemented)

```java
public interface Scenario {
    ScenarioDescriptor descriptor();                 // id, lab, params with bounds, variants, invariant
    ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception;
}
```

The descriptor is the single source of truth for: the UI configuration form, server-side parameter
validation, and documentation shown next to results. Scenarios are discovered through a static
registry (`ScenarioCatalog`) rather than classpath scanning, so the set of executable code is
explicit and reviewable.

## 7. Run lifecycle (implemented)

1. `POST /api/experiments/{id}/runs` validates the variant and every parameter against the
   descriptor bounds → row inserted as `QUEUED`.
2. `RunQueue` worker picks the run, marks it `RUNNING`, creates a private temp directory.
3. `RunnerLauncher` builds the command line (`java -Xmx… -XX:ActiveProcessorCount=… -cp <runner>
   dev.forge.lab.runner.RunnerMain`), clears the environment, writes the `RunSpec` to stdin.
4. Events streamed on stdout are persisted as `run_event` rows as they arrive.
5. On `result`, the result and environment are stored and status becomes `SUCCEEDED`. A
   non-zero exit, protocol error, output-cap breach, or timeout produces `FAILED`/`TIMED_OUT`
   with the reason recorded; the process tree is destroyed.
6. The temp directory is deleted.

## 8. Planned components

| Component | Phase | Notes |
|---|---|---|
| k6 adapter | 2 (deferred) | Needs Docker access from the runner and a target reachable from a container; to be designed with the dedicated runner container (Phase 9). The open-model generator, embedded target and pinned-baseline regression gate are implemented. |
| Failure Lab | 6 | Fault injection inside an embedded downstream service; client resilience policies. |
| AI Investigator | 7 | Provider-agnostic adapter; prompts built only from stored artifacts, with citations to run ids. |
| Challenge Arena | 8 | Challenges = scenario + verification predicate over recorded results. |
