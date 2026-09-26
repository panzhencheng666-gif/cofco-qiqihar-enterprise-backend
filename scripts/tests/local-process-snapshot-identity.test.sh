#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${backend_root}/scripts/local-process-ownership.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/cofco-snapshot-identity.XXXXXX")"
child_pid=""
cleanup() {
  if [[ -n "$child_pid" ]]; then
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
  rm -rf -- "$test_root"
}
trap cleanup EXIT

mkdir -p "$test_root/current" "$test_root/other"
(
  cd "$test_root/current"
  exec sleep 60
) &
child_pid=$!
pid_file="$test_root/service.pid"
record_owned_process "$pid_file" "$child_pid" "$child_pid" 45678 'test service'
owned_listener_runs_from_directory "$pid_file" "$child_pid" 45678 \
  'test service' "$test_root/current"
if owned_listener_runs_from_directory "$pid_file" "$child_pid" 45678 \
  'test service' "$test_root/other"; then
  echo 'Wrong snapshot directory was accepted' >&2
  exit 1
fi
if owned_listener_runs_from_directory "$pid_file" "$child_pid" 45678 \
  'other service' "$test_root/current"; then
  echo 'Wrong service ownership was accepted' >&2
  exit 1
fi

# Replace the directory path while the process still holds the old inode.
mv "$test_root/current" "$test_root/previous"
mkdir "$test_root/current"
if owned_listener_runs_from_directory "$pid_file" "$child_pid" 45678 \
  'test service' "$test_root/current"; then
  echo 'A replaced snapshot path was accepted' >&2
  exit 1
fi
echo 'SNAPSHOT_IDENTITY_OK owned process rejected wrong and replaced directories'
