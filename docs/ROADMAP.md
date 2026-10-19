# Roadmap

A phase is **done** only when every acceptance criterion is met and verified by an automated test
or a recorded manual check. Status values: `done`, `in progress`, `not started`.

| Phase | Theme | Status |
|---|---|---|
| 0 | Architecture & documentation | done |
| 1 | Working experiment platform | done (CI workflow not yet executed on GitHub) |
| 2 | Performance lab (HTTP) | in progress (core done; k6 adapter and pinned-baseline regression detection not started) |
| 3 | Concurrency lab | in progress (core exercises done) |
| 4 | Database studio | not started |
| 5 | JVM lab | not started |
| 6 | Failure lab | not started |
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
- [ ] k6 adapter (`grafana/k6` container) against the same target when Docker is available.
- [ ] Regression detection against a pinned baseline.

## Phase 3 — Concurrency lab
- [x] Lost update: unsynchronized / volatile / synchronized / ReentrantLock / AtomicLong / LongAdder.
- [x] Stress mode with invariant reporting ("violated in k of N trials").
- [x] Explorer mode: exhaustive bounded interleaving enumeration with counterexample trace.
- [x] Deadlock: nested locks vs ordered locks vs tryLock-with-backoff; JVM deadlock detection with thread dump artifact.
- [x] Visibility: plain vs volatile stop flag.
- [x] Every scenario: invariant, broken + fixed implementation, automated verification, explanation.
- [x] Bounded queue / backpressure: unbounded vs blocking vs load shedding under open-loop overload (latency from intended time), plus a check-then-act bounded-buffer race with explorer counterexample.
- [ ] Starvation and an explicit livelock exercise. *(livelock is already observable: the tryLock explorer reports schedules that exceed the step bound)*

## Phase 4 — Database studio
- [ ] Disposable lab database and restricted role; seeded synthetic transaction history.
- [ ] EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) capture and plan-tree visualisation.
- [ ] Index on/off comparison; OFFSET vs keyset pagination; N+1 demonstration.
- [ ] Isolation level and lock contention demonstrations.

## Phase 5 — JVM lab
- [ ] JFR capture in child JVM; allocation, GC, lock-contention and CPU sample views.
- [ ] Warm-up/JIT demonstration; allocation-heavy vs allocation-light variants.

## Phase 6 — Failure lab
- [ ] Fault-injecting proxy (latency, errors, refusal) in front of embedded downstream.
- [ ] Retry storm, timeout, circuit-breaker scenarios with recovery timeline.

## Phase 7 — AI investigator
- [ ] Provider-agnostic adapter (API + local model); evidence bundle built only from stored artifacts.
- [ ] Rule-based analyser that works with no AI provider configured.

## Phase 8 — Challenge arena
- [ ] Challenges defined as scenario + verification predicate; completion only by measured criteria.

## Phase 9 — Hardening
- [ ] Dedicated runner container without database route; seccomp profile; accessibility audit; full E2E tests.
