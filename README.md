# FORGE

**Framework for Optimization, Reliability, Growth & Experimentation**

> Don't just write software. Understand how it behaves.

FORGE is a personal engineering laboratory for studying how real code behaves: you state a
hypothesis, run a parameterised experiment in an isolated JVM, and inspect the evidence — per-trial
measurements, invariant checks, controlled-schedule traces, JMH data, JFR profiles, thread dumps, and the exact
environment — then compare a candidate against a baseline with honest statistics.

Every number FORGE shows comes from a run you can inspect. Modules that do not exist yet say so;
there are no placeholder charts.

## What works today

[![ci](https://github.com/EklavyaGoyal17/Forge/actions/workflows/ci.yml/badge.svg)](https://github.com/EklavyaGoyal17/Forge/actions/workflows/ci.yml)

| Area | What it is |
|---|---|
| **Experiment platform** | Experiments, runs, lifecycle, events, artifacts in PostgreSQL (Flyway). A durable run queue (`FOR UPDATE SKIP LOCKED`). Every run executes in its **own JVM**: heap cap, empty environment, private temp dir, timeout, output caps, process-tree kill. In Docker that JVM runs in a **separate runner container** on an internal network. That network reaches only a disposable lab database: not the platform database, not the internet, and not the API. |
| **Statistics** | Baseline vs candidate: bootstrap CI of the ratio of medians and Mann–Whitney U, with parameter and environment diffs. A regression gate against a pinned baseline that refuses a verdict when runs are not comparable. |
| **Concurrency Lab** | Lost update, deadlock, visibility, backpressure, **livelock** and **starvation** (24 implementations). Stress mode on real cores, a deterministic **interleaving explorer** (every schedule, with a counterexample), and a **liveness explorer** that builds the full state graph and finds fair cycles without progress. |
| **Performance Lab** | An embedded loopback HTTP service under an open-model load. Latency is measured from the intended send time (coordinated-omission corrected, HdrHistogram), alongside service time, queue wait and the Little's-law bound. **JFR, CPU utilisation and wall-clock worker-thread sampling** show whether a plateau is computing or waiting. A **k6 cross-check** drives the same target with k6 to test FORGE's own generator: they agree on a healthy service (p99 23.6 vs 22.9 ms). Under overload k6 drops 37% of iterations instead of reporting the wait. |
| **JVM Lab** | Java Flight Recorder inside the runner, summarised there: hot methods, allocation sites, GC pauses, contended locks. JIT warm-up from the first call (tiered / C1 / C2 / interpreter). Allocation-heavy vs allocation-light code. Lock contention. |
| **Database Studio** | A disposable lab database with a restricted role and seeded data per run. Index experiments, OFFSET vs keyset pagination, N+1, and SQL lost updates under isolation levels. EXPLAIN (ANALYZE, BUFFERS) plan trees. |
| **Failure Lab** | A dependency started inside the run gets latency, HTTP 500s, dropped connections or a restart injected. Six client policies are compared: no timeout, timeout, immediate retries, backoff with jitter, retry budget, circuit breaker. A per-window recovery timeline makes **metastable retry storms** visible. |
| **Algorithm Lab** | Membership lookup with 4 data structures, measured with JMH. |
| **AI Investigator** | An evidence bundle with a citation id on every item, and rule-based findings that work with no model at all. An optional model (Anthropic or a local server) gets its answers checked: citations that don't exist, and numbers that aren't in the evidence, are flagged. |
| **Challenge Arena** | Eight measurable problems. Hypothesis first, hints counted, review questions recorded. Complete **only** when every criterion passes on recorded runs. |
| **Engineering Review Board, notebook, reports** | An explicit evidence rubric with reviewer questions, a searchable notebook, and Markdown report export (PDF via print). |
| **Web UI** | Dashboard, experiment workspace, per-lab evidence views, compare, investigate, review, command palette, keyboard shortcuts, dark and light themes. Automated accessibility checks (axe-core, WCAG 2.1 AA token contrast). |
| **Tests** | 201 JVM tests (Testcontainers PostgreSQL 17), 67 UI tests. CI also starts the compose stack, asserts container hardening and runner network isolation (`scripts/verify_isolation.sh`), and runs every scenario through the API in the runner container. |
| Not done | Browser automation (Playwright), a screen-reader audit, a verified call to the real Anthropic API, and per-run sandboxes (gVisor/microVM) for untrusted code. FORGE runs no uploaded code. See [ROADMAP](docs/ROADMAP.md) and the residual risks in [SECURITY](docs/SECURITY.md). |

### The three showcase demonstrations (Dashboard)

* **The Invisible Race**: the explorer finds 4 of 6 losing schedules for `balance = balance + 1`. The fixed version is checked exhaustively (226 schedules) and under 20 stress trials. The demo also explains why tests still prove nothing about the absence of races.
* **The Query That Wouldn't Scale**: at page 2,000, OFFSET reads 100,000 rows to return 50 (13.6 ms) while keyset reads 50 (1.9 ms). Both plans are shown side by side.
* **The Performance Mystery**: a 4-thread service plateaus at 190 req/s of 300 offered, with p99 at the 2 s timeout. JFR's CPU samples point at `CpuWork.spin`. The wall-clock sampler shows the workers are **96.7% blocked in `Thread.sleep` waiting on the downstream call**, at 19% CPU. A non-blocking downstream with the same 4 threads serves 300 req/s at p99 23.5 ms.

## Quick start

Prerequisites: JDK 21+ (built and tested on 25), Maven 3.9+, Node 22+, Docker.

### Everything in containers

```bash
docker compose up --build
```

Then open http://127.0.0.1:8090. PostgreSQL is published on `127.0.0.1:5442`. Four services start: `postgres` (FORGE's data), `server`, `runner` (where every run executes) and `lab-postgres` (Database Studio's disposable server, in memory). Set `FORGE_RUNNER_AGENT_TOKEN`, `FORGE_LAB_ADMIN_PASSWORD` and `FORGE_DB_PASSWORD` in a `.env` file to replace the public defaults.

### Development (hot reload)

```bash
docker compose up -d postgres
mvn -q install -DskipTests
FORGE_RUNNER_CLASSPATH="$PWD/forge-lab/target/forge-lab-0.1.0-SNAPSHOT-runner.jar" java -jar forge-server/target/forge-server.jar
cd forge-ui && npm install && npm run dev
```

Then open http://127.0.0.1:5190 (Vite proxies `/api` to the server on 8090).
`mvn -pl forge-server spring-boot:run` also works without `FORGE_RUNNER_CLASSPATH`; the runner then
reuses the development classpath.

`FORGE_RUNNER_CLASSPATH` must be an absolute path: the runner JVM starts in its private per-run
directory, so a relative path fails with `ClassNotFoundException: RunnerMain`.

Do not point two FORGE servers at the same database (for example the compose container and a
development server): both claim queued runs, so a run may execute on the other server's runner.
Give the second server its own database and port, e.g.
`FORGE_DB_URL=jdbc:postgresql://127.0.0.1:5442/forge_dev FORGE_PORT=8091`.

Database Studio runs use a separate `forge_lab` database and a `forge_lab_runner` role that the
server creates on first use, so the platform user must be allowed to create roles and databases
(the compose user is). Names: `FORGE_LAB_DB`, `FORGE_LAB_ROLE`; disable with
`FORGE_LAB_DB_ENABLED=false`. Roles are cluster-wide: two servers on one PostgreSQL should use
different role names, or each rotates the other's password.

Ports were chosen to avoid 5173/8080/5433, which other local projects commonly use.

## A first experiment: *The Invisible Race*

1. Dashboard → **The Invisible Race → Start**. Write your hypothesis *before* running anything.
2. Queue a **baseline** run of the `Plain field` variant (stress mode), then a **candidate** run of
   `AtomicLong CAS loop` with the same parameters.
3. Queue an **exploratory** run in **explore** mode to enumerate every interleaving and see a
   concrete losing schedule.
4. Open **Compare**, then **Review board**, write your notebook entries, and export the **Report**.

What this machine actually showed (i7-12650H, JDK 25, 4 threads × 200,000 deposits, 20 trials):
the racy variant violated the invariant in only **1 of 20** trials (but lost up to ~52 % of the
deposits when it did), and its median "throughput" of ~9 billion deposits/s is physically
implausible for one update per deposit. Both facts point to the JIT having transformed the loop.
FORGE's plausibility check flags this. Explorer mode, by contrast, deterministically found
**64 of 70** schedules violating the invariant for 2 threads × 2 deposits. Your numbers will differ;
that is the point.

## AI Investigator (optional model)

The investigator always works without a model: its findings come from explicit rules over stored
evidence. To also ask a model questions about an experiment, set one of these before starting FORGE:

```bash
FORGE_AI_PROVIDER=anthropic FORGE_AI_API_KEY=sk-ant-... docker compose up -d
```

```bash
FORGE_AI_PROVIDER=local FORGE_AI_LOCAL_URL=http://127.0.0.1:11434/v1 FORGE_AI_MODEL=llama3.1 mvn -pl forge-server spring-boot:run
```

`anthropic` sends the experiment's evidence (as shown in the UI) to the Anthropic API and is billed to
your key; the default model is `claude-opus-5-5` (`FORGE_AI_MODEL` overrides it). `local` must point at
a loopback address. Answers are checked against the evidence: citations must exist, and numbers that do
not appear in the evidence are flagged. See docs/SECURITY.md → "AI Investigator data flow".

## Architecture in one paragraph

A Spring Boot 4 server (Java 21 bytecode) stores experiments and runs in PostgreSQL. A single
worker claims queued runs from the database and launches each one as a **separate JVM** running
`forge-lab`'s `RunnerMain`, with an explicit heap limit, an empty environment and a private
working directory. The runner re-validates its input, executes a built-in scenario, and streams
JSON-lines events, artifacts and the final result back over stdout. In Docker, the server streams the run to an agent in the runner container instead, which launches the JVM there (ADR-016). The React UI renders only what
the API returns. See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for the decisions and trade-offs.

```
forge-lab/      plain Java: scenario SPI, scenarios, controlled scheduler, statistics, runner
forge-server/   Spring Boot: REST API, PostgreSQL/Flyway, run queue, launcher, comparison, review, reports,
                investigator, challenges
forge-ui/       React + TypeScript + Vite + Tailwind
docs/           architecture, methodology, security, testing, roadmap
scripts/        e2e_smoke.py — every scenario through the API of a running deployment
```

## Documentation

* [Architecture & decisions](docs/ARCHITECTURE.md)
* [Experiment methodology](docs/EXPERIMENT_METHODOLOGY.md) — statistics and why results may not generalise
* [Security & isolation model](docs/SECURITY.md) — including what is **not** protected
* [Testing](docs/TESTING.md)
* [Roadmap](docs/ROADMAP.md)

## Safety

FORGE binds to `127.0.0.1` and has no authentication. It runs only built-in, reviewed scenarios;
there is no code upload. A child JVM is **not** a security sandbox — read
[SECURITY.md](docs/SECURITY.md) before changing either of those facts.

## Scope

This is an independent educational project. It contains no employer branding, systems, data or
proprietary material.
