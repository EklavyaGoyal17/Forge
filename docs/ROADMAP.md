# Roadmap

A phase is **done** only when every acceptance criterion is met and verified by an automated test
or a recorded manual check. Status values: `done`, `in progress`, `not started`.

| Phase | Theme | Status |
|---|---|---|
| 0 | Architecture & documentation | done |
| 1 | Working experiment platform | done (CI workflow not yet executed on GitHub) |
| 2 | Performance lab (HTTP) | done except the k6 adapter (deferred by design, see below) |
| 3 | Concurrency lab | done |
| 4 | Database studio | done |
| 5 | JVM lab | done |
| 6 | Failure lab | done |
| 7 | AI investigator | not started |
| 8 | Challenge arena | not started |
| 9 | Hardening | not started |

`[x]` = implemented and verified by an automated test or a recorded check; `[~]` = implemented but not
fully verified; `[ ]` = not done.

## Phase 0 — Architecture
- [x] README, ARCHITECTURE, ROADMAP, SECURITY, TESTING, EXPERIMENT_METHODOLOGY exist and agree with the code.

## Phase 1 — Working experiment platform
- [x] Experiment CRUD persisted in PostgreSQL via Flyway-managed schema.
- [x] Scenario catalog served from the descriptor registry; UI form generated from descriptors.
- [x] Runs validated against descriptor bounds; invalid input → HTTP 400 with field errors.
- [x] Runs executed in a child JVM with heap/CPU flags, empty env, private temp dir, timeout.
- [x] Lifecycle `QUEUED → RUNNING → SUCCEEDED/FAILED/TIMED_OUT/CANCELLED` persisted with events.
- [x] A timed-out run is killed and recorded as `TIMED_OUT` (integration test).
- [x] Results visualised per trial; baseline vs candidate comparison with bootstrap CI.
- [x] Markdown report export.
- [ ] `docker compose up` starts PostgreSQL + server + UI.
- [~] CI workflow runs backend and frontend tests. *(written; the same commands pass locally; not yet executed on GitHub — no remote configured)*

## Phase 2 — Performance lab
- [x] Open-model (constant arrival rate) HTTP load generator with coordinated-omission-correct latency (HdrHistogram). *(`OpenLoopGeneratorTest`: virtual-clock server stall with hand-derived p99 — 1 ms from actual send vs 910 ms from intended send)*
- [x] Embedded loopback target service with configurable CPU work, blocking I/O, and worker-pool size. *(bound to 127.0.0.1 on an ephemeral port inside the runner JVM, checked at start-up; four designs: small blocking pool, 8× pool, non-blocking downstream, virtual threads)*
- [x] Client-observed latency vs server-side service time vs queue wait reported separately. *(plus worker time per request and the Little's-law capacity it implies)*
- [x] Warm-up and sustained phases, repeated trials, p50/p90/p95/p99, error rate. *(`PerformanceScenarioTest` checks structure and request accounting. Recorded end-to-end check on 2026-10-19 through the API on the development machine: at 300 req/s, 4 workers and a 20 ms downstream, the blocking pool completed a median of 188 req/s against a Little's-law bound of 188 req/s, and its p99 reached the 2 s timeout while a send-time measurement reported 346 ms; the non-blocking design completed 300 req/s with p99 24.5 ms)*
- [ ] k6 adapter (`grafana/k6` container) against the same target when Docker is available. *(deferred by design: a `grafana/k6` container would need Docker access from the runner (the runner has an empty environment and no Docker socket — see SECURITY.md) and, on Docker Desktop, a target bound beyond 127.0.0.1 so the container can reach it. Both weaken the isolation model; revisit together with the dedicated runner container in Phase 9)*
- [x] Regression detection against a pinned baseline. *(pin a succeeded run per experiment; every other succeeded run is gated: REGRESSION only when comparable — same parameters, scenario version, environment — and the primary metric's bootstrap CI excludes 1.0 on the worse side, or when an invariant the baseline held is violated. `RegressionGateTest` covers the rules; `ExperimentFlowIntegrationTest.pinnedBaselineGatesEveryOtherRun` covers pin/unpin, NOT_COMPARABLE on a parameter change, the report section and deletion of a pinned experiment)*

## Phase 3 — Concurrency lab
- [x] Lost update: unsynchronized / volatile / synchronized / ReentrantLock / AtomicLong / LongAdder.
- [x] Stress mode with invariant reporting ("violated in k of N trials").
- [x] Explorer mode: exhaustive bounded interleaving enumeration with counterexample trace.
- [x] Deadlock: nested locks vs ordered locks vs tryLock-with-backoff; JVM deadlock detection with thread dump artifact.
- [x] Visibility: plain vs volatile stop flag.
- [x] Every scenario: invariant, broken + fixed implementation, automated verification, explanation.
- [x] Bounded queue / backpressure: unbounded vs blocking vs load shedding under open-loop overload (latency from intended time), plus a check-then-act bounded-buffer race with explorer counterexample.
- [x] Livelock exercise. *(`concurrency.livelock`: polite flags vs randomised back-off vs Peterson's algorithm. `LivenessExplorer` (ADR-012) builds the complete state graph of both threads looping forever: polite flags keep mutual exclusion but have a fair cycle without progress; Peterson is livelock- and starvation-free on the complete graph — `LivenessScenariosTest`)*
- [x] Starvation exercise. *(`concurrency.starvation`: readers-preference, writer-preference, FIFO ticket, CAS-loop ticket, JDK `ReentrantReadWriteLock` non-fair/fair. Explorer: readers-preference can starve the writer, writer-preference the readers, the CAS-loop ticket a thread whose CAS keeps losing (lock-free is not wait-free — FORGE's first ticket lock had this bug and the explorer found it); the fetch-and-add ticket lock starves nobody. Stress: writer wait p99/max with censored waits, read throughput and peak concurrent readers)*
- [x] Recorded check (2026-10-21, Docker container, 4 CPUs, default parameters): polite flags — 21 states, a 9-step fair livelock cycle reachable after 4 steps; Peterson — 48 states, complete, no livelock or starvation. Starvation explorer: readers-preference 94 states, writer-preference 237, ticket 244 (no starvation), CAS-loop ticket 2,168 (every thread can starve). Stress (3 readers, a write every 5 ms, 1 s trials): readers-preference completed 0 of 1 requested writes in 5 of 5 trials (the request was still waiting after ≈ 995 ms); every other lock completed 199 of 199 with writer wait p99 0.11–0.14 ms; the ticket lock read 46.9 k/s with 1 reader inside at a time vs 140–145 k/s and 3 concurrent readers for the sharing locks (JDK fair: 110 k/s). Livelock stress never livelocked on real cores (polite flags: longest wait 0.29 ms, 2.16 back-offs per entry).
- [x] Liveness explorer unit tests on hand-drawn state graphs: exact state/edge counts, a one-step livelock cycle, an unfair cycle that is correctly *not* a livelock, barging-lock starvation, safety in every reachable state, and refusal to judge a program that is not a function of its schedule (`LivenessExplorerTest`).

## Phase 4 — Database studio
- [x] Disposable lab database and restricted role; seeded synthetic transaction history. *(`LabDatabaseServiceTest` against PostgreSQL 17: the role works in its own database but cannot connect to the platform database or create databases/roles, has its statement/lock timeouts, and a restart rotates its password and sweeps leftover run schemas. Each run seeds its own schema from `dataSeed` and drops it)*
- [x] EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) capture and plan-tree visualisation. *(per-trial capture; UI plan tree with rows vs estimate, per-node self time and buffers — `database.test.tsx`)*
- [x] Index on/off comparison; OFFSET vs keyset pagination; N+1 demonstration. *(`database.index-lookup`, `database.pagination` with a page-depth sweep and a page-equality invariant, `database.n-plus-one` with a graph-equality invariant and measured round-trip time)*
- [x] Isolation level and lock contention demonstrations. *(`database.lost-update`: read-modify-write under READ COMMITTED vs SELECT … FOR UPDATE vs atomic UPDATE vs REPEATABLE READ / SERIALIZABLE with 40001 retries. Lock waits are included in the per-deposit time, not measured separately)*

Recorded end-to-end check (2026-10-19, development machine, PostgreSQL 17.11 in Docker): all four scenarios succeeded through the API as the restricted lab role (`platformIsolated: true`). Index lookup median 17.8 ms without an index vs 0.95 ms with the covering index (identical probe checksums across all four variants with `dataSeed` 1); OFFSET page 2,000 read 100,000 rows for 50 (13.6 ms) vs keyset 50 rows (1.9 ms); N+1 issued 101 statements (105 ms, of which ≈ 80 % is the round-trip floor) vs 2 batched (3.6 ms); read-modify-write lost 568–585 of 800 deposits per trial while atomic UPDATE and SERIALIZABLE + retry lost none.

## Phase 5 — JVM lab
- [x] JFR capture in child JVM; allocation, GC, lock-contention and CPU sample views. *(scenarios start a `jdk.jfr.Recording` around their measured trials, write it to the run directory and summarise it there with `jdk.jfr.consumer` — ADR-011. `JfrSummariserTest` records a real file around certain workloads (explicit GC, a monitor and a ReentrantLock held while another thread waits, allocation, a spin loop, markers); `jvm.test.tsx` covers the hot-method, allocation, GC-timeline and lock views. Measured artefacts found and handled on real data: each thread's first allocation sample carries its whole earlier allocation (41.8 MB credited to an allocation-free loop) and is discarded; JFR's own start/dump code is kept out by analysing only the marker window; workers reading their own `ThreadMXBean` counters contended inside the JDK, so the driver reads them)*
- [x] Warm-up/JIT demonstration; allocation-heavy vs allocation-light variants. *(`jvm.warmup`: every invocation timed from the first in a fresh class-loader copy of the kernel per trial, tiered / C1 only / C2 only / interpreter only via allow-listed per-variant JVM flags, compilations placed on the invocation axis. `jvm.allocation`: boxed stream / IntStream / loop and `String +=` / StringBuilder / presized builder with exact per-thread allocation, JFR allocation sites and per-trial GC pauses, result invariant. `jvm.lock-contention` (synchronized / ReentrantLock / per-thread state) backs the contention view with real data. `JvmScenariosTest` and `RunnerLauncherTest` assert structure, invariants and flag handling, never timings)*

Recorded end-to-end check (2026-10-19, development machine: i7-12650H, 16 logical processors, Windows 11, JDK 25.0.3, G1, 256 MB heap, default parameters, 5 trials): all 13 variants succeeded through the API with every invariant held, and the variant flags appeared in the recorded launch flags. Warm-up (10,000-element kernel, 3,000 calls): tiered ran interpreted for ~7 calls (first call 371 µs), C1 tier-3 code from invocation 7–8, C2 OSR code from 206–286 and standard C2 from 313–379, steady state 15.3 µs reached at invocation 363 (median); C2 only reached C2 code at invocation 24 and steady state at 156; C1 only settled at 15.9 µs; `-Xint` stayed at 273 µs with no compilation (847 ms per trial vs 60 ms tiered). Allocation (10⁶ values): boxed stream allocated 39.98 MB per operation exactly (JFR estimate 1,927 MB vs 1,999 MB over the run; `Long` and `Integer` at `Long.valueOf` / `Integer.valueOf`), 30 garbage collections, 12.1 ms/op; IntStream 423 B/op and the loop 79 B/op with no GC, 5.6 and 5.3 ms/op. Render (4,000 values): `String +=` 55.2 MB/op, 6.2 ms/op, 30 collections; StringBuilder 0.10 MB/op and presized 0.06 MB/op, ≈ 0.08 ms/op. Contention (4 threads × 200,000): synchronized 8.9 M increments/s with 64,711 BLOCKED entries (JFR: 110 monitor waits ≥ 0.1 ms on `SynchronizedAccumulator.add`); ReentrantLock 30.5 M/s with 17,967 waits as parks; per-thread state 392 M/s with none.

## Phase 6 — Failure lab
- [x] Fault injection (latency, HTTP 500s, dropped connections, restart with refused connections) into an embedded dependency that each trial starts inside the runner JVM on loopback. *(ADR-013: injected inside the target rather than by a TCP proxy — same client-side behaviour for HTTP, no Docker access or network path needed. `FailureLabTest` exercises each fault against a real loopback server)*
- [x] Timeout, immediate retries, backoff with jitter, retry budget and circuit breaker, with a per-window recovery timeline (success, latency, attempts sent, dependency backlog, breaker state) and a defined recovery time. *(`failure.unstable-dependency`; breaker state machine tested on a fake clock; request accounting, amplification, budget and short-circuiting tested on whole runs; recovery times are not asserted)*
- [x] Recorded check (2026-10-21, Docker container, 4 CPUs): 100 req/s against a dependency with 4 workers × 10 ms (400 req/s healthy), 800 ms latency injected for 2 s (capacity 4.9 req/s), 2 s request deadline, 3 trials per policy, results identical to within a few tenths of a percent across trials. No timeout: 91.5% success, recovered 1.28–1.40 s after the fault. Timeout only: 49.5%, 43% of the dependency's work wasted. Immediate retries: 16.7%, 3.28 attempts per request, backlog ≈ 900, 86% wasted, **did not recover in 3 of 3 trials** although the fault had ended (metastable); backoff with jitter: the same (16.7%, 3.24, no recovery) — jitter spreads retries but does not reduce them. Retry budget: 46%, 1.11 attempts per request, recovered after 1.52–1.64 s. Circuit breaker: 58.2%, 0.65 attempts per request, backlog 36, recovered after 560 ms in every trial.
- [x] Measurement artifact found and fixed: the first recorded run showed caller latency of ≈ 13 ms in some trials and ≈ 55 ms in others for the same 10 ms service. Cause: Nagle's algorithm on the JDK `HttpServer` (status line and body written separately) meeting the client's delayed ACK on Linux (up to 40 ms). Both embedded servers now set `TCP_NODELAY` through `LoopbackHttpServer`; after the fix every trial's pre-fault latency is 12–16 ms. The Performance Lab scenario is bumped to v2 because its measurements changed. The earlier experiment is kept and annotated in the notebook as evidence.
- [x] Found while testing: the JDK `HttpClient` resends an idempotent GET by itself when a pooled connection closes before any response. Runs report "received − sent" so retries below the policy's own logic are visible.

## Phase 7 — AI investigator
- [ ] Provider-agnostic adapter (API + local model); evidence bundle built only from stored artifacts.
- [ ] Rule-based analyser that works with no AI provider configured.

## Phase 8 — Challenge arena
- [ ] Challenges defined as scenario + verification predicate; completion only by measured criteria.

## Phase 9 — Hardening
- [ ] Dedicated runner container without database route; seccomp profile; accessibility audit; full E2E tests.
