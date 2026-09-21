#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() { echo "FAIL: $1" >&2; exit 1; }
assert_contains() {
  grep -Fq "$2" "${backend_root}/$1" || fail "$1 does not contain $2"
}

plist="${backend_root}/ops/launchd/com.cofco.qiqihar.risk-training-node.local.plist"
[[ -f "$plist" ]] || fail "missing training node plist"
plutil -lint "$plist" >/dev/null || fail "training node plist is invalid"
[[ "$(plutil -extract Label raw -o - "$plist")" == \
  "com.cofco.qiqihar.risk-training-node.local" ]] || fail "unexpected label"
[[ "$(plutil -extract RunAtLoad raw -o - "$plist")" == "true" ]] || fail "RunAtLoad required"
[[ "$(plutil -extract KeepAlive raw -o - "$plist")" == "true" ]] || fail "KeepAlive required"

assert_contains scripts/risk-training-node-local.sh "RISK_TRAINING_CLOUD_URL"
assert_contains scripts/risk-training-node-local.sh "Refusing to replace an unowned listener"
assert_contains scripts/run-risk-training-node-launch-agent.sh "training-node.env"
assert_contains scripts/run-risk-training-node-launch-agent.sh "remote_worker.py"
assert_contains scripts/run-risk-training-node-launch-agent.sh "risk-mlx-trainer.py"
assert_contains scripts/healthcheck-risk-training-node-local.sh '127.0.0.1:${trainer_port}/health'

echo "RISK_TRAINING_NODE_LOCAL_CONTRACT_OK"
