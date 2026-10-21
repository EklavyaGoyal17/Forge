#!/usr/bin/env python3
"""End-to-end smoke test of a running FORGE deployment (the Docker image in CI, or a local stack).

Runs one short, real run of every registered scenario through the HTTP API and checks that it
succeeds, then checks the UI, the AI investigator (rule-based) and the Challenge Arena endpoints.
Python standard library only. Exits non-zero on the first category of failure, after reporting all.

    python scripts/e2e_smoke.py http://127.0.0.1:8090 [--expect-runner-container]

With --expect-runner-container, every run must report that it executed in the dedicated runner
container (the compose deployment), not in the server process.
"""
import json
import sys
import time
import urllib.error
import urllib.request

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
BASE = (ARGS[0] if ARGS else "http://127.0.0.1:8090").rstrip("/")
EXPECT_RUNNER_CONTAINER = "--expect-runner-container" in sys.argv
DEADLINE_S = 240

# One fast, representative run per scenario. Parameters are clamped to each descriptor's bounds,
# so the script keeps working if a bound changes; scenarios not listed run their first variant with
# defaults and one trial.
RUNS = {
    "concurrency.lost-update": [("unsynchronized", {"mode": "explore", "exploreThreads": 2, "exploreDeposits": 1}, 1, 0),
                                ("atomic-cas", {"mode": "stress", "threads": 2, "depositsPerThread": 10000}, 2, 0)],
    "concurrency.deadlock": [("ordered-locks", {"mode": "explore"}, 1, 0)],
    "concurrency.visibility": [("volatile", {"spinBeforeStopMs": 50, "observeWindowMs": 500}, 1, 0)],
    "concurrency.backpressure": [("drop-newest", {"mode": "stress", "durationMs": 300}, 1, 0)],
    "concurrency.livelock": [("peterson", {"mode": "explore"}, 1, 0)],
    "concurrency.starvation": [("ticket", {"mode": "explore"}, 1, 0)],
    "algorithms.lookup": [("hash-set", {"size": 100, "iterationMs": 100, "forks": 1}, 1, 1)],
    "performance.thread-pool-starvation": [("async", {"rate": 100, "warmupMs": 200, "durationMs": 500}, 1, 0)],
    "performance.generator-crosscheck": [("forge", {"rate": 100, "warmupMs": 200, "durationMs": 1000}, 1, 0),
                                         ("k6", {"rate": 100, "warmupMs": 0, "durationMs": 1000}, 1, 0)],
    "database.index-lookup": [("covering-index", {"rows": 10000, "accounts": 100, "queriesPerTrial": 10}, 1, 0)],
    "database.pagination": [("keyset", {"rows": 10000, "page": 10, "pagesPerTrial": 5}, 1, 0)],
    "database.n-plus-one": [("batched", {"rows": 10000, "accounts": 10, "loadsPerTrial": 3}, 1, 0)],
    "database.lost-update": [("atomic-update", {"workers": 2, "depositsPerWorker": 10}, 1, 0)],
    "jvm.warmup": [("tiered", {"invocations": 200, "workSize": 100}, 1, 0)],
    "jvm.allocation": [("primitive-loop", {"elements": 10000, "opsPerTrial": 1}, 1, 0)],
    "jvm.lock-contention": [("striped", {"threads": 2, "incrementsPerThread": 1000, "workRounds": 0}, 1, 0)],
    "failure.unstable-dependency": [("circuit-breaker", {"fault": "errors", "errorPercent": 100, "rate": 20, "warmupMs": 200,
                                                         "durationMs": 1000, "faultStartMs": 200, "faultDurationMs": 300}, 1, 0)],
}


def call(method, path, body=None, raw=False):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        content = r.read()
        if raw:
            return r.status, r.headers.get("Content-Type", ""), content.decode("utf-8", "replace")
        return json.loads(content) if content else None


def clamp(descriptor, params):
    out = {}
    specs = {p["name"]: p for p in descriptor["parameters"]}
    for k, v in params.items():
        p = specs.get(k)
        if p is None:
            raise SystemExit(f"{descriptor['id']}: smoke parameter {k} is not declared")
        if p["type"] == "INTEGER":
            v = max(p["min"], min(p["max"], v))
        out[k] = v
    return out


def wait_ready():
    for _ in range(120):
        try:
            call("GET", "/api/system")
            return
        except Exception:
            time.sleep(2)
    raise SystemExit("server did not become ready")


def main():
    wait_ready()
    failures = []
    descriptors = {d["id"]: d for d in call("GET", "/api/scenarios")}
    missing = set(RUNS) - set(descriptors)
    if missing:
        failures.append(f"scenarios expected by the smoke test are not registered: {sorted(missing)}")
    experiments = []
    queued = []
    for sid, d in descriptors.items():
        exp = call("POST", "/api/experiments", {"title": f"e2e: {sid}", "question": "Does it run end to end?",
                                                 "hypothesis": "every scenario completes in the deployed image", "scenarioId": sid, "tags": ["e2e"]})
        experiments.append(exp["id"])
        for variant, params, trials, warmup in RUNS.get(sid, [(d["variants"][0]["id"], {}, 1, 0)]):
            body = {"variant": variant, "params": clamp(d, params), "trials": trials, "warmupTrials": min(warmup, d["trials"]["maxWarmup"])}
            run = call("POST", f"/api/experiments/{exp['id']}/runs", body)
            queued.append((sid, variant, run["id"]))

    start = time.time()
    for sid, variant, rid in queued:
        while True:
            run = call("GET", f"/api/runs/{rid}")["run"]
            if run["status"] not in ("QUEUED", "RUNNING"):
                break
            if time.time() - start > DEADLINE_S * 3:
                failures.append(f"{sid}/{variant}: still {run['status']} after {DEADLINE_S * 3} s")
                break
            time.sleep(1)
        ok = run["status"] == "SUCCEEDED"
        executor = ((run.get("environment") or {}).get("launch") or {}).get("executor", "?")
        print(f"{'ok  ' if ok else 'FAIL'} {sid:38s} {variant:22s} {run['status']:10s} [{executor}]" + ("" if ok else f" — {run.get('statusReason')}"))
        if not ok:
            failures.append(f"{sid}/{variant}: {run['status']} {run.get('statusReason')}")
        elif EXPECT_RUNNER_CONTAINER and "runner container" not in executor:
            failures.append(f"{sid}/{variant}: executed in '{executor}', expected the runner container")

    # UI: the SPA and one hashed asset are served by the server
    status, ctype, html = call("GET", "/", raw=True)
    if status != 200 or "text/html" not in ctype or 'id="root"' not in html:
        failures.append(f"UI index not served correctly ({status}, {ctype})")
    else:
        import re
        assets = re.findall(r'src="(/assets/[^"]+\.js)"', html)
        if not assets:
            failures.append("UI index references no script asset")
        else:
            s, c, _ = call("GET", assets[0], raw=True)
            if s != 200 or "javascript" not in c:
                failures.append(f"UI asset {assets[0]} not served ({s}, {c})")
    s, c, html = call("GET", "/experiments/does-not-matter", raw=True)
    if s != 200 or 'id="root"' not in html:
        failures.append("client-side route is not forwarded to index.html")

    # AI investigator (rule-based) and Challenge Arena
    analysis = call("GET", f"/api/experiments/{experiments[0]}/investigation")
    ids = {e["id"] for e in analysis["evidence"]}
    if not analysis["findings"] or any(c not in ids for f in analysis["findings"] for c in f["citations"]):
        failures.append("investigator findings missing or citing unknown evidence")
    answer = call("POST", f"/api/experiments/{experiments[0]}/investigation/questions", {"question": "What should I run next?"})
    if answer["provider"] != "rules":
        failures.append(f"expected the rule-based investigator, got {answer['provider']}")
    challenges = call("GET", "/api/challenges")
    if len(challenges) != 8:
        failures.append(f"expected 8 challenges, got {len(challenges)}")

    for e in experiments:
        try:
            call("DELETE", f"/api/experiments/{e}")
        except urllib.error.HTTPError:
            pass

    print()
    if failures:
        print("FAILURES:")
        for f in failures:
            print(" -", f)
        sys.exit(1)
    print(f"all {len(queued)} runs of {len(descriptors)} scenarios succeeded; UI, investigator and challenges OK")


if __name__ == "__main__":
    main()
