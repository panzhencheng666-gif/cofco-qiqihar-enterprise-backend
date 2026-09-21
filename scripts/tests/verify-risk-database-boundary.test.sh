#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
role_sql="${backend_root}/ops/risk-intelligence/create-risk-runtime-roles.sql"

fail() {
  echo "[FAIL] $*" >&2
  exit 1
}

[[ -f "$role_sql" ]] || fail "risk role SQL is missing: $role_sql"
content="$(<"$role_sql")"

assert_contains() {
  local expected=$1
  [[ "$content" == *"$expected"* ]] || fail "role SQL is missing: $expected"
}

assert_not_contains() {
  local rejected=$1
  [[ "$content" != *"$rejected"* ]] || fail "role SQL contains forbidden grant: $rejected"
}

assert_contains "CREATE ROLE qiqihar_risk_runtime NOLOGIN"
assert_contains "CREATE ROLE qiqihar_risk_runtime_login LOGIN"
assert_contains "GRANT qiqihar_risk_runtime TO qiqihar_risk_runtime_login"
assert_contains "GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime"
assert_contains "ALTER DEFAULT PRIVILEGES FOR ROLE qiqihar_migration_owner IN SCHEMA risk"
assert_contains "statement_timeout='15s'"
assert_contains "lock_timeout='2s'"
assert_contains "idle_in_transaction_session_timeout='30s'"
assert_not_contains "GRANT INSERT ON ALL TABLES IN SCHEMA platform"
assert_not_contains "GRANT UPDATE ON ALL TABLES IN SCHEMA overview"
assert_not_contains "GRANT DELETE ON ALL TABLES IN SCHEMA"

echo "RISK_DATABASE_ROLE_CONTRACT_OK"
