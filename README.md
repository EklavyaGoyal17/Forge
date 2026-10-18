# FORGE

**Framework for Optimization, Reliability, Growth & Experimentation**

> Don't just write software. Understand how it behaves.

FORGE is a personal engineering laboratory for studying how real code behaves: you state a
hypothesis, run a parameterised experiment in an isolated JVM, and inspect the evidence — per-trial
measurements, invariant checks, controlled-schedule traces, JMH data, thread dumps, and the exact
environment — then compare a candidate against a baseline with honest statistics.

Every number FORGE shows comes from a run you can inspect. Modules that do not exist yet say so;
there are no placeholder charts.

## What works today

| Area | Status |
|---|---|
| Experiment platform: experiments, runs, lifecycle, results, events, artifacts (PostgreSQL + Flyway) | ✅ |
| Isolated runner: child JVM per run, heap cap, empty environment, private temp dir, timeout, output caps, process-tree kill | ✅ |
| Durable run queue (DB rows claimed with `FOR UPDATE SKIP LOCKED`), serial by default, cancel | ✅ |
| Baseline vs candidate comparison: bootstrap CI of ratio of medians, Mann–Whitney U, parameter/environment diffs | ✅ |
| Engineering Review Board: explicit evidence rubric + reviewer questions | ✅ |
| Markdown report export (PDF via browser print) | ✅ |
| **Concurrency Lab** — lost update (6 variants), deadlock (3), visibility (3), backpressure under overload (5); stress mode + deterministic interleaving explorer | ✅ |
| **Algorithm Lab** — membership lookup (4 variants) measured with JMH | ✅ |
| Web UI: dashboard, experiment workspace, run evidence, compare, notebook, review, command palette, keyboard shortcuts, dark/light | ✅ |
| Performance lab (HTTP load), Database Studio, JVM lab (JFR), Failure lab, AI investigator, Challenge arena | planned — see [ROADMAP](docs/ROADMAP.md) |

## Quick start

Prerequisites: JDK 21+ (built and tested on 25), Maven 3.9+, Node 22+, Docker.

### Everything in containers

```bash
docker compose up --build
```

Then open http://127.0.0.1:8090. PostgreSQL is published on `127.0.0.1:5442`.

### Development (hot reload)

```bash
docker compose up -d postgres
mvn -q install -DskipTests
FORGE_RUNNER_CLASSPATH=forge-lab/target/forge-lab-0.1.0-SNAPSHOT-runner.jar java -jar forge-server/target/forge-server.jar
cd forge-ui && npm install && npm run dev
```

Then open http://127.0.0.1:5190 (Vite proxies `/api` to the server on 8090).
`mvn -pl forge-server spring-boot:run` also works without `FORGE_RUNNER_CLASSPATH`; the runner then
reuses the development classpath.

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

## Architecture in one paragraph

A Spring Boot 4 server (Java 21 bytecode) stores experiments and runs in PostgreSQL. A single
worker claims queued runs from the database and launches each one as a **separate JVM** running
`forge-lab`'s `RunnerMain`, with an explicit heap limit, an empty environment and a private
working directory. The runner re-validates its input, executes a built-in scenario, and streams
JSON-lines events, artifacts and the final result back over stdout. The React UI renders only what
the API returns. See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for the decisions and trade-offs.

```
forge-lab/      plain Java: scenario SPI, scenarios, controlled scheduler, statistics, runner
forge-server/   Spring Boot: REST API, PostgreSQL/Flyway, run queue, launcher, comparison, review, reports
forge-ui/       React + TypeScript + Vite + Tailwind
docs/           architecture, methodology, security, testing, roadmap
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
