#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() { echo "FAIL: $1" >&2; exit 1; }
assert_contains() {
  grep -Fq "$2" "${backend_root}/$1" || fail "$1 does not contain $2"
}

plist="${backend_root}/ops/launchd/com.cofco.qiqihar.risk-training-node.local.plist"
[[ -f "$plist" ]] || fail "missing training node plist"
python3 - "$plist" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as source:
    config = plistlib.load(source)
if config.get("Label") != "com.cofco.qiqihar.risk-training-node.local":
    sys.exit("FAIL: unexpected label")
for key in ("RunAtLoad", "KeepAlive"):
    if config.get(key) is not True:
        sys.exit(f"FAIL: {key} required")
PY

assert_contains scripts/risk-training-node-local.sh "RISK_TRAINING_CLOUD_URL"
assert_contains scripts/risk-training-node-local.sh "Refusing to replace an unowned listener"
assert_contains scripts/risk-training-node-local.sh "active-expert-claim.json"
assert_contains scripts/run-risk-training-node-launch-agent.sh "training-node.env"
assert_contains scripts/run-risk-training-node-launch-agent.sh "remote_worker.py"
assert_contains scripts/run-risk-training-node-launch-agent.sh "risk-mlx-trainer.py"
assert_contains scripts/healthcheck-risk-training-node-local.sh '127.0.0.1:${trainer_port}/health'

echo "RISK_TRAINING_NODE_LOCAL_CONTRACT_OK"
