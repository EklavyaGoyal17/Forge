# Security and Isolation Model

FORGE executes workloads that are *designed* to misbehave: they spin, deadlock, allocate, and
saturate thread pools. This document describes how that is contained, and what is **not**
contained. Read the "Residual risks" section before exposing FORGE to anyone but yourself.

## 1. Threat model

| Asset | Threat | Primary control |
|---|---|---|
| Developer machine stability | Runaway CPU/memory/threads from an experiment | Child JVM with `-Xmx`, `-Xss`, `-XX:ActiveProcessorCount`, `-XX:+ExitOnOutOfMemoryError`, wall-clock timeout, process-tree kill |
| FORGE's own data | Experiment code writing to platform tables | Child JVM receives no database credentials; empty environment |
| Host secrets | Env vars (`AWS_*`, tokens), home-directory files | Environment cleared before launch; working directory and `java.io.tmpdir` set to a private per-run directory |
| Other hosts on the network | Load tests or fault injection aimed at real services | No user-supplied targets in current phases; HTTP targets (Phase 2) are embedded services bound to loopback inside the child JVM; any future external target requires an explicit allowlist entry |
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

### Container deployment (`docker-compose.yml`)
* Server image runs as a non-root user.
* `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, `read_only: true` root filesystem
  with a size-limited `tmpfs` for `/tmp`, `pids_limit`, `mem_limit`, `cpus`.
* PostgreSQL is reachable only on the internal compose network and is published to the host on
  `127.0.0.1` only.
* No privileged containers, no Docker socket mounts.

## 3. Residual risks (honest list)

1. **A child JVM is not a security sandbox.** It can read any file the server's OS user can read
   and open network connections. The controls above limit *accidents* and *resource abuse* by
   vetted scenarios; they would not stop deliberately malicious code. This is why code upload is
   not offered.
2. **Shared network namespace.** In the compose deployment, child JVMs share the server
   container's network and could reach PostgreSQL. They do not receive credentials, but defence in
   depth would place the runner in its own container on a network with no route to the database.
   (Planned: dedicated `forge-runner` container, `network_mode: none` for non-HTTP scenarios.)
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
* Destructive operations against any database other than the disposable lab database are
  forbidden; destructive operations against the lab database require explicit confirmation in the
  UI (Phase 4).
