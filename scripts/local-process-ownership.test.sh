#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${backend_root}/scripts/local-process-ownership.sh"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/cofco-ownership-test.XXXXXX")"
pid_file="${test_root}/stale.pid"
child_file="${test_root}/child.pid"
root_pid=""
child_pid=""

cleanup() {
  if [[ "$child_pid" =~ ^[0-9]+$ ]]; then
    kill -TERM "$child_pid" 2>/dev/null || true
  fi
  if [[ "$root_pid" =~ ^[0-9]+$ ]]; then
    kill -TERM "$root_pid" 2>/dev/null || true
  fi
  rm -f "$pid_file" "$child_file"
  rmdir "$test_root" 2>/dev/null || true
}
trap cleanup EXIT

bash -c 'sleep 300 & printf "%s\n" "$!" > "$1"; wait' _ "$child_file" &
root_pid=$!

for _ in {1..40}; do
  [[ -s "$child_file" ]] && break
  sleep 0.05
done
child_pid="$(cat "$child_file")"

record_owned_process "$pid_file" "$root_pid" "$child_pid" 65432 "test service"

kill -TERM "$child_pid"
kill -TERM "$root_pid" 2>/dev/null || true
wait "$root_pid" 2>/dev/null || true

reconcile_stale_owned_process "$pid_file" "test service"

[[ ! -e "$pid_file" ]] || {
  echo "[FAIL] a provably dead ownership record was not removed" >&2
  exit 1
}

echo "[OK] provably dead ownership records are reconciled safely"
