#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() { echo "FAIL: $1" >&2; exit 1; }
assert_contains() {
  grep -Fq -- "$2" "${backend_root}/$1" || fail "$1 does not contain $2"
}
assert_not_contains() {
  if grep -Fq -- "$2" "${backend_root}/$1"; then
    fail "$1 contains forbidden text $2"
  fi
}

deploy_script="ops/risk-intelligence/deploy-cloud-runtime.sh"
unit="ops/systemd/cofco-risk-intelligence.service"

[[ -x "${backend_root}/${deploy_script}" ]] || fail "cloud deployment script is missing or not executable"
[[ -f "${backend_root}/${unit}" ]] || fail "cloud systemd unit is missing"

assert_contains risk-intelligence-service/src/main/resources/application.yml \
  'address: ${RISK_SERVER_ADDRESS:127.0.0.1}'
assert_contains "$deploy_script" 'RISK_TRAINING_REMOTE_NODE_ENABLED=true'
assert_contains "$deploy_script" 'RISK_DB_USERNAME=qiqihar_risk_runtime_login'
assert_contains "$deploy_script" 'verify-risk-database-boundary.sh'
assert_contains "$deploy_script" 'LOCAL_ACCEPTANCE'
assert_contains "$deploy_script" 'assert_existing_service_healthy'
assert_contains "$deploy_script" 'rollback_service'
assert_contains "$deploy_script" 'RDS has fewer than 10 free connections'
assert_contains "$unit" '--memory=384m'
assert_contains "$unit" '--cpus=0.75'
assert_contains "$unit" '127.0.0.1:19384:63184'
assert_contains "$unit" 'RISK_SERVER_ADDRESS=0.0.0.0'
assert_not_contains "$unit" '--privileged'
assert_not_contains "$unit" '0.0.0.0:19384'

bash -n "${backend_root}/${deploy_script}"

echo "RISK_INTELLIGENCE_CLOUD_CONTRACT_OK"
