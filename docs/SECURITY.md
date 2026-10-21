# Security and Isolation Model

FORGE executes workloads that are *designed* to misbehave: they spin, deadlock, allocate, and
saturate thread pools. This document describes how that is contained, and what is **not**
contained. Read the "Residual risks" section before exposing FORGE to anyone but yourself.

## 1. Threat model

| Asset | Threat | Primary control |
|---|---|---|
| Developer machine stability | Runaway CPU/memory/threads from an experiment | Child JVM with `-Xmx`, `-Xss`, `-XX:ActiveProcessorCount`, `-XX:+ExitOnOutOfMemoryError`, wall-clock timeout, process-tree kill |
| FORGE's own data | Experiment code writing to platform tables | Child JVM never receives platform credentials; empty environment. Database Studio runs receive credentials (over stdin) for a restricted lab role that cannot connect to the platform database |
| Host secrets | Env vars (`AWS_*`, tokens), home-directory files | Environment cleared before launch; working directory and `java.io.tmpdir` set to a private per-run directory |
| Other hosts on the network | Load tests or fault injection aimed at real services | No user-supplied targets; the Performance Lab's HTTP target is an embedded service the scenario starts inside the child JVM, bound to 127.0.0.1 on an ephemeral port (verified at start-up), and its load is bounded (≤ 2 000 req/s, ≤ 256 connections, ≤ 10 s sustained per trial, ≤ 13 trials); k6 is given only that loopback URL; in compose the runner container has no route off its internal network; any future external target requires an explicit allowlist entry |
| Server availability | Many queued runs, oversized outputs | Bounded queue, serial execution, stdout/stderr byte caps, parameter bounds |
| Browser users | XSS through artifacts | Artifacts served as `text/plain` with `X-Content-Type-Options: nosniff`; UI renders text, never HTML from results |

**Out of scope for the current phases:** multi-user deployments, authentication, and execution of
user-uploaded code. FORGE binds to `127.0.0.1` by default and has no login. Do not expose it on a
network interface.

## 2. Implemented controls

### Execution
* **Only built-in, reviewed scenarios run.** They are registered explicitly in `ScenarioCatalog`;
  there is no classpath scanning and no code upload endpoint.
* **Parameter validation.** Every parameter is checked server-side against type, min and max in the
  scenario descriptor. Unknown parameters and unknown variants are rejected (HTTP 400). Trial
  counts and timeouts have global ceilings regardless of scenario.
* **Child JVM per run** (see ARCHITECTURE.md ADR-001) launched with:
  * `-Xmx` (default 256 MB, ceiling 1 GB), `-Xss` cap, `-XX:+ExitOnOutOfMemoryError`
  * `-XX:ActiveProcessorCount` (default: all host processors, configurable ceiling)
  * `-Djava.io.tmpdir=<private run dir>`, working directory = private run dir
  * **empty environment** except the minimum the JVM needs on the platform (`SYSTEMROOT` on
    Windows, `PATH` reduced to the JDK `bin` directory)
* **Timeouts.** Each run has a wall-clock timeout (scenario default, global ceiling 10 min).
  On expiry the process and all descendants (`ProcessHandle.descendants()`) are destroyed,
  forcibly after a 2 s grace period.
* **Output caps.** Protocol stdout capped (default 16 MB), stderr capped (256 KB); exceeding the
  stdout cap kills the run.
* **No shell.** Commands are built as argument arrays for `ProcessBuilder`; no string is ever
  passed through `cmd.exe` or `/bin/sh`.
* **Per-variant JVM flags are allow-listed.** JVM Lab JIT-mode variants add one flag to the runner
  command line. The flag comes from the compiled scenario descriptor for the validated variant id —
  never from request data — and the launcher rejects anything outside `-Xint`,
  `-XX:-TieredCompilation`, `-XX:TieredStopAtLevel=1` (execution-mode switches; no agents,
  diagnostic options or file paths). A test checks every flag declared in the catalog.
* **JFR recordings stay in the run directory.** JVM Lab scenarios record Java Flight Recorder into
  the private work directory (size-capped at 64 MB by `Recording.setMaxSize`), summarise it in the
  runner and return only the summary; the file is deleted with the directory. Stack frames in the
  summary name only JDK and FORGE classes — there is no user code to leak.

* **Fault injection targets only a service the run itself starts.** Failure Lab scenarios start
  their dependency (`InventoryService`) inside the runner JVM, bound to `127.0.0.1` on an ephemeral
  port and checked to be loopback at start-up; faults are switched on and off inside that object.
  There is no parameter that names a host, port or URL, so a run cannot be pointed at anything else.
* **Retry storms are bounded.** A Failure Lab run is refused when it could send more than 40,000
  attempts (rate × duration × (1 + retries) × trials), because each timed-out attempt closes a TCP
  connection and consumes a local port for the TIME_WAIT period.

### AI Investigator data flow
* **Off by default.** With `FORGE_AI_PROVIDER=none` nothing is sent anywhere; the rule-based analysis
  runs in the server.
* **Configured from the server environment only.** No endpoint accepts a provider, URL, model or key,
  so a request cannot redirect evidence or read the key; `/api/investigator/status` reports the provider
  and model, never the key. The key is not stored in the database or logged. Runner JVMs start with an
  empty environment, so scenarios never see it.
* **What is sent** (provider `anthropic`): the experiment's evidence bundle exactly as the UI shows it —
  question, hypothesis, the user's notebook text, scenario caveats, run parameters, environment
  summaries (CPU model, OS, JVM), metric summaries, observations and comparison results — plus the
  question. No source code, artifacts or raw trial data beyond that. The UI states this next to the Ask
  button. Treat the notebook as content that leaves the machine when this provider is enabled.
* **Local provider** (`local`): the base URL must be a loopback address (checked at start-up), so
  evidence stays on the host.
* **Prompt injection.** Evidence includes user-written text (notes, labels). The system prompt tells
  the model to treat evidence as data, and every answer is checked: citations must resolve to evidence
  ids and numbers that are not in the evidence are flagged. Model output is rendered as text, never as
  HTML, and is never executed or used to start runs.

### Container deployment (`docker-compose.yml`)
Runs do not execute in the server container. Four services on two networks (ARCHITECTURE.md ADR-016):

| Service | Networks | Holds | Reaches |
|---|---|---|---|
| `postgres` (platform database) | `platform` | experiments, runs, notebook | — |
| `server` (API + UI) | `platform`, `runners` | platform DB credentials, lab admin credentials, AI key (if configured) | postgres, lab-postgres, runner agent |
| `runner` (runner agent; every runner JVM is its child) | `runners` only | the agent token | lab-postgres; the server's IP (the API refuses it, below) |
| `lab-postgres` (Database Studio's disposable server, data on tmpfs) | `runners` only | synthetic lab data | — |

* `runners` is an **internal** Docker network: no route to the internet and no external DNS. The
  runner cannot resolve or reach the platform database.
* **The API refuses the runner.** `RunnerNetworkGuard` resolves the runner's address
  (`FORGE_DENY_REQUESTS_FROM=runner`) and answers 403 to any request from it, so code in a run cannot
  queue runs or read experiments. The server opens every connection to the agent; it never needs one
  from it.
* **The agent** (`RunnerAgent`) accepts a run only with the shared token (`X-Forge-Runner-Token`,
  compared in constant time), validates the run id, heap and timeout against its own ceilings, and
  derives JVM flags from its own scenario catalog — never from the request. It applies the same child-JVM
  controls as above (empty environment, heap cap, timeout, process-tree kill, output caps), limits
  concurrent runs, and kills a run whose caller disconnects.
* **k6** (Performance Lab cross-check) is a static binary in the image, started by the runner JVM
  with an empty environment, usage reporting off, and a script FORGE generates; the launcher refuses
  any URL that is not `http://127.0.0.1:<port>`, the embedded target in the same run.
* Both FORGE containers run as a non-root user with `cap_drop: [ALL]`,
  `security_opt: [no-new-privileges:true]`, a `read_only: true` root filesystem with a size-limited
  `tmpfs` for `/tmp`, `pids_limit`, `mem_limit` and `cpus` (the runner's limits bound all runs at once).
* The platform database and the server are published to the host on `127.0.0.1` only; the runner
  and the lab database are not published at all.
* No privileged containers, no Docker socket mounts.
* Docker's default **seccomp** profile applies (verified at runtime: `Seccomp: 2`, filter mode).
* CI asserts all of the above on the running stack (`scripts/verify_isolation.sh` in the job
  "Hardened container, end to end"): hardening of both containers, the runner reaching lab-postgres but
  not resolving `postgres` or `example.com`, no connection to `1.1.1.1`, and HTTP 403 from the API to
  the runner while the host gets 200.
* Override `FORGE_RUNNER_AGENT_TOKEN`, `FORGE_LAB_ADMIN_PASSWORD` and `FORGE_DB_PASSWORD` (see `.env`
  support in Docker Compose); the defaults in `docker-compose.yml` are public.

## 3. Residual risks (honest list)

1. **A child JVM is not a security sandbox.** It can read any file the server's OS user can read
   and open network connections. The controls above limit *accidents* and *resource abuse* by
   vetted scenarios; they would not stop deliberately malicious code. This is why code upload is
   not offered.
2. **What the runner container can still reach.** Runs share one runner container, so a run can
   see a concurrent run's files in `/tmp` (the agent runs them one at a time by default) and
   could open TCP connections to lab-postgres and to the server's port. On lab-postgres it receives
   only a restricted per-run role, but the lab *admin* password is a default unless overridden, and a
   run that guessed it could damage the other lab data — disposable, on tmpfs, but present. The
   server answers the runner with 403 at the HTTP layer, keyed on the runner's resolved address; a
   container that took another address on the `runners` network would not be refused. Agent traffic
   is plain HTTP on the internal network, authenticated by a shared token. Outside compose (`mvn
   spring-boot:run`), runner JVMs are still children of the server process on the host, with the
   controls of §2 "Execution" only.
3. **Docker containers are not a perfect sandbox either.** They share the host kernel. Kernel
   vulnerabilities, misconfiguration, or a mounted Docker socket defeat them. For untrusted code
   the planned path is gVisor (`runsc`) or a microVM (Firecracker), plus seccomp profiles.
4. **CPU caps on the host process are advisory.** `-XX:ActiveProcessorCount` changes what the JVM
   *believes* it has (thread pool sizing, GC threads); it does not prevent a scenario from
   creating more runnable threads than cores. Real CPU limits come from the container `cpus` limit.
5. **No authentication.** Anyone who can reach the port can start runs. Bind to loopback only.
6. **Windows hosts:** process-tree termination relies on `ProcessHandle.descendants()`; a
   grandchild that re-parents itself could escape. Built-in scenarios do not do this (JMH forks
   are direct children and are destroyed with the tree).

## 4. Rules for adding a scenario

* No network access except to services the scenario itself starts on loopback.
* No file access outside `java.io.tmpdir`.
* All loops must observe `RunContext.checkCancelled()` *or* be bounded by a deadline, except
  scenarios that intentionally demonstrate non-termination (e.g. visibility bugs) — those must run
  the offending thread as a daemon and report via the deadline.
* No `System.exit` — the runner owns process exit codes.
* Database scenarios use only the lab database, and only their own per-run schema
  (`LabSession`), which is dropped at the end of the run. They never touch the platform database
  or other schemas. (An earlier plan required UI confirmation for destructive lab-database
  operations; it is unnecessary because nothing outside the run's own schema is ever modified.)
* Lab-database timeouts (statement, lock, idle-in-transaction) are role settings, so a scenario
  cannot run unbounded statements even if it forgets to check its deadline.
