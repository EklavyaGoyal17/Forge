#!/usr/bin/env bash
# Asserts the isolation described in docs/SECURITY.md §2 against a running `docker compose up` stack.
# Used by CI (e2e job); run it locally the same way: bash scripts/verify_isolation.sh
set -euo pipefail

fail() { echo "FAIL: $*" >&2; exit 1; }

for svc in server runner; do
  c=$(docker compose ps -q "$svc")
  [ -n "$c" ] || fail "$svc is not running"
  test "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$c")" = true || fail "$svc: root filesystem is writable"
  test "$(docker inspect -f '{{.HostConfig.Privileged}}' "$c")" = false || fail "$svc: privileged"
  docker inspect -f '{{json .HostConfig.CapDrop}}' "$c" | grep -q '"ALL"' || fail "$svc: capabilities not dropped"
  docker inspect -f '{{json .HostConfig.SecurityOpt}}' "$c" | grep -q 'no-new-privileges:true' || fail "$svc: no-new-privileges missing"
  test "$(docker inspect -f '{{.Config.User}}' "$c")" = forge || fail "$svc: not running as forge"
  test "$(docker inspect -f '{{.HostConfig.PidsLimit}}' "$c")" -gt 0 || fail "$svc: no pids limit"
  test "$(docker inspect -f '{{.HostConfig.Memory}}' "$c")" -gt 0 || fail "$svc: no memory limit"
  docker compose exec -T "$svc" sh -c '
    grep -E "^(Seccomp|NoNewPrivs|CapEff):" /proc/1/status
    test "$(id -u)" != 0
    grep -Eq "^Seccomp:[[:space:]]+2$" /proc/1/status
    grep -Eq "^NoNewPrivs:[[:space:]]+1$" /proc/1/status
    grep -Eq "^CapEff:[[:space:]]+0+$" /proc/1/status
    ! touch /app/should-fail 2>/dev/null' || fail "$svc: process-level checks"
  echo "ok   $svc container hardening"
done

# The runner network is internal: the runner reaches the lab database and the server's agent client, nothing else.
docker compose exec -T runner sh -c '
  set -u
  getent hosts lab-postgres >/dev/null                         || { echo "lab-postgres not resolvable"; exit 1; }
  nc -z -w 3 lab-postgres 5432                                 || { echo "lab-postgres not reachable"; exit 1; }
  if getent hosts postgres >/dev/null; then echo "platform database is resolvable from the runner"; exit 1; fi
  if getent hosts example.com >/dev/null; then echo "internet DNS resolves from the runner"; exit 1; fi
  if wget -q -T 3 -O /dev/null http://1.1.1.1/ 2>/dev/null; then echo "internet reachable by IP from the runner"; exit 1; fi
  status=$(wget -q -T 5 -S -O /dev/null http://server:8090/api/scenarios 2>&1 | head -1 || true)
  echo "runner -> server API: $status"
  echo "$status" | grep -q " 403" || { echo "the API did not refuse the runner"; exit 1; }
' || fail "runner network isolation"
echo "ok   runner network isolation (lab-postgres only; no platform DB, no internet, API refuses it)"

code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8090/api/scenarios)
test "$code" = 200 || fail "host -> API returned $code"
echo "ok   host -> API 200"
