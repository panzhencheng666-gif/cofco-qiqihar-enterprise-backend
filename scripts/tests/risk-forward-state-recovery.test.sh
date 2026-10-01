#!/usr/bin/env bash
set -euo pipefail

# Exercise recovery against a disposable database after V218 has created a
# candidate, but before the forward manual-promotion guard has been installed.
database_url=${RISK_RECOVERY_TEST_URL:?Dedicated recovery test URL required}
database_user=${RISK_RECOVERY_TEST_USERNAME:?Test database user required}
database_password=${RISK_RECOVERY_TEST_PASSWORD:?Test database password required}
case "$database_url" in
  jdbc:postgresql://127.0.0.1:*/risk_forward_state_test) ;;
  *) echo "DEDICATED_RECOVERY_DATABASE_REQUIRED" >&2; exit 1 ;;
esac
backend_root=$(cd "$(dirname "$0")/../.." && pwd)
export PGPASSWORD="$database_password"
psql_url=${database_url#jdbc:}
sql() { psql --no-psqlrc --set=ON_ERROR_STOP=1 --username="$database_user" "$psql_url" "$@"; }
sql --quiet <<'SQL'
CREATE SCHEMA risk;
CREATE TABLE risk.ai_model(model_id uuid PRIMARY KEY, model_code text, status_code text);
CREATE TABLE risk.ai_training_policy(model_id uuid PRIMARY KEY, enabled boolean, auto_activation_enabled boolean, updated_at timestamptz);
INSERT INTO risk.ai_model VALUES ('21800000-0000-0000-0000-000000000001','qiliang-risk-llm-v1','DRAFT');
INSERT INTO risk.ai_training_policy VALUES ('21800000-0000-0000-0000-000000000001',false,true,now());
SQL

# Source only the recovery function; never execute the actual deploy entrypoint.
helper_source=$(mktemp)
trap 'rm -f "$helper_source"' EXIT
awk '/^hold_forward_model_runtime_state_using/ { printing=1 } printing { print } printing && /^}/ { exit }' "$backend_root/ops/risk-intelligence/deploy-cloud-runtime.sh" > "$helper_source"
[[ -s "$helper_source" ]] || { echo "RECOVERY_FUNCTION_MISSING" >&2; exit 1; }
source "$helper_source"
hold_forward_model_runtime_state_using "$database_url" "$database_user" "$database_password"
actual=$(sql --tuples-only --no-align --command="SELECT model.status_code || '|' || policy.enabled::text || '|' || policy.auto_activation_enabled::text FROM risk.ai_model model JOIN risk.ai_training_policy policy USING(model_id)")
[[ "$actual" == 'DRAFT|false|false' ]] || { echo "PARTIAL_MIGRATION_PROMOTED_MODEL_OR_POLICY" >&2; exit 1; }

# A database update failure must propagate instead of reporting false recovery.
sql --quiet --command='DROP TABLE risk.ai_training_policy'
if hold_forward_model_runtime_state_using "$database_url" "$database_user" "$database_password" >/dev/null 2>&1; then
  echo "DATABASE_FAILURE_WAS_HIDDEN" >&2
  exit 1
fi
echo "RISK_FORWARD_STATE_RECOVERY_FAIL_CLOSED_OK"
