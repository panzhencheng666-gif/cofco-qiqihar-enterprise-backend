#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
role_sql="${backend_root}/ops/risk-intelligence/create-risk-runtime-roles.sql"

require_value() {
  local name=$1
  local value=${!name:-}
  if [[ -z "$value" ]]; then
    echo "Required environment variable is missing: $name" >&2
    exit 2
  fi
}

for variable in \
  RISK_DB_ADMIN_URL RISK_DB_ADMIN_USERNAME RISK_DB_ADMIN_PASSWORD \
  RISK_DB_RUNTIME_PASSWORD RISK_EXPECTED_DATABASE; do
  require_value "$variable"
done

command -v psql >/dev/null 2>&1 || {
  echo "psql is required to configure risk database access" >&2
  exit 2
}
[[ -f "$role_sql" ]] || {
  echo "Risk role SQL is missing: $role_sql" >&2
  exit 2
}

admin_url=${RISK_DB_ADMIN_URL#jdbc:}
admin_url=${admin_url%%\?*}
PGPASSWORD="$RISK_DB_ADMIN_PASSWORD" psql \
  --no-psqlrc --set=ON_ERROR_STOP=1 \
  --username="$RISK_DB_ADMIN_USERNAME" \
  --set=database_name="$RISK_EXPECTED_DATABASE" \
  --set=migration_owner="$RISK_DB_ADMIN_USERNAME" \
  --set=risk_runtime_password="$RISK_DB_RUNTIME_PASSWORD" \
  --file="$role_sql" "$admin_url"

echo "RISK_DATABASE_ACCESS_CONFIGURED database=${RISK_EXPECTED_DATABASE} role=qiqihar_risk_runtime_login"
