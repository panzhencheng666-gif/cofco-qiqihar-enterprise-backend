#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$backend_root"

fail() {
  echo "weekly imagery installer contract failed: $*" >&2
  exit 1
}

assert_file() {
  [[ -f "$1" ]] || fail "missing $1"
}

assert_contains() {
  local file="$1" expected="$2"
  grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

assert_file ops/systemd/cofco-weekly-imagery.service
assert_file ops/systemd/cofco-weekly-imagery.timer
assert_file scripts/install-weekly-imagery-worker.sh

assert_contains ops/systemd/cofco-weekly-imagery.timer 'OnCalendar=*-*-01 01:00:00 Asia/Shanghai'
assert_contains ops/systemd/cofco-weekly-imagery.timer 'RandomizedDelaySec=0'
assert_contains ops/systemd/cofco-weekly-imagery.timer 'Persistent=true'
assert_contains ops/systemd/cofco-weekly-imagery.service 'ProtectSystem=strict'
assert_contains ops/systemd/cofco-weekly-imagery.service 'ReadWritePaths=/var/lib/cofco/imagery'
assert_contains ops/systemd/cofco-weekly-imagery.service 'flock --nonblock'
assert_contains ops/systemd/cofco-weekly-imagery.service '/usr/bin/python3.11'
assert_contains scripts/install-weekly-imagery-worker.sh 'install -m 600'
assert_contains scripts/install-weekly-imagery-worker.sh 'for command in python3.11'
assert_contains scripts/install-weekly-imagery-worker.sh 'setfacl -m u:cofco-imagery:--x /var/lib/cofco'
assert_contains scripts/install-weekly-imagery-worker.sh 'runuser -u cofco-imagery -- test -x /var/lib/cofco'
assert_contains scripts/install-weekly-imagery-worker.sh '/usr/local/bin/verify-weekly-imagery-release'
assert_contains scripts/install-weekly-imagery-worker.sh 'systemctl enable --now cofco-weekly-imagery.timer'
assert_contains scripts/install-weekly-imagery-worker.sh '--root /var/lib/cofco/imagery'
assert_contains scripts/install-weekly-imagery-worker.sh '--dry-run'

bash -n scripts/install-weekly-imagery-worker.sh
systemd-analyze verify \
  "${backend_root}/ops/systemd/cofco-weekly-imagery.service" \
  "${backend_root}/ops/systemd/cofco-weekly-imagery.timer" >/dev/null 2>&1 || {
    command -v systemd-analyze >/dev/null 2>&1 && fail "systemd unit verification failed"
  }

echo "WEEKLY_IMAGERY_INSTALLER_CONTRACT_OK"
