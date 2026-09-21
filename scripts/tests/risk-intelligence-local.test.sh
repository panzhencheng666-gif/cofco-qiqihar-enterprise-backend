#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

assert_file_contains() {
  local file=$1
  local expected=$2
  [[ -f "${backend_root}/${file}" ]] || fail "missing ${file}"
  grep -Fq "$expected" "${backend_root}/${file}" ||
    fail "${file} does not contain ${expected}"
}

plist="${backend_root}/ops/launchd/com.cofco.qiqihar.risk-intelligence.local.plist"
[[ -f "$plist" ]] || fail "missing risk LaunchAgent plist"
plutil -lint "$plist" >/dev/null || fail "risk LaunchAgent plist is invalid"
[[ "$(plutil -extract Label raw -o - "$plist")" == \
  "com.cofco.qiqihar.risk-intelligence.local" ]] || fail "unexpected LaunchAgent label"
[[ "$(plutil -extract RunAtLoad raw -o - "$plist")" == "true" ]] || fail "RunAtLoad is required"
[[ "$(plutil -extract KeepAlive raw -o - "$plist")" == "true" ]] || fail "KeepAlive is required"

assert_file_contains "scripts/risk-intelligence-local.sh" '127.0.0.1:${port}/actuator/health'
assert_file_contains "scripts/risk-intelligence-local.sh" "Refusing to replace"
assert_file_contains "scripts/risk-intelligence-local.sh" 'mv -fh "${current_release}.new" "$current_release"'
assert_file_contains "scripts/run-risk-intelligence-launch-agent.sh" "local-runtime.env"
assert_file_contains "scripts/run-risk-intelligence-launch-agent.sh" "stat -f '%Lp'"
assert_file_contains "scripts/run-risk-intelligence-launch-agent.sh" "risk-mlx-trainer.py"
assert_file_contains "scripts/risk-intelligence-local.sh" "RISK_TRAINING_ENABLED=true"
assert_file_contains "scripts/risk-intelligence-local.sh" "RISK_TRAINING_NODE_TOKEN"
assert_file_contains "scripts/risk-intelligence-local.sh" "RISK_LLM_TRAINER_URL=http://127.0.0.1:"
assert_file_contains "scripts/healthcheck-risk-intelligence-local.sh" "/api/v1/risk-intelligence/operations/boundary"
assert_file_contains "scripts/healthcheck-risk-intelligence-local.sh" "RISK_DATABASE_BOUNDARY_OK"
assert_file_contains "scripts/healthcheck-risk-intelligence-local.sh" "RISK_LLM_TRAINER_HEALTH_OK"

echo "RISK_INTELLIGENCE_LOCAL_CONTRACT_OK"
