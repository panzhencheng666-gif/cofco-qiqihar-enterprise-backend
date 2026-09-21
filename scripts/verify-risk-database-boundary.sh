#!/usr/bin/env bash
set -euo pipefail

require_value() {
  local name=$1
  local value=${!name:-}
  if [[ -z "$value" ]]; then
    echo "Required environment variable is missing: $name" >&2
    exit 2
  fi
}

for variable in RISK_DB_URL RISK_DB_USERNAME RISK_DB_PASSWORD RISK_EXPECTED_DATABASE; do
  require_value "$variable"
done

command -v psql >/dev/null 2>&1 || {
  echo "psql is required to verify the risk database boundary" >&2
  exit 2
}

database_url=${RISK_DB_URL#jdbc:}
query() {
  local sql=$1
  PGPASSWORD="$RISK_DB_PASSWORD" psql --no-psqlrc --tuples-only --no-align \
    --set=ON_ERROR_STOP=1 --username="$RISK_DB_USERNAME" "$database_url" \
    --command="$sql"
}

identity="$(query "SELECT current_database()||'|'||current_user")"
actual_database=${identity%%|*}
actual_user=${identity#*|}
if [[ "$actual_database" != "$RISK_EXPECTED_DATABASE" ]]; then
  echo "Unexpected database: $actual_database" >&2
  exit 1
fi

unexpected_table_writes="$(query "
  SELECT namespace.nspname||'.'||relation.relname
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
  WHERE relation.relkind IN ('r','p','v','m','S')
    AND namespace.nspname NOT IN ('risk','pg_catalog','information_schema')
    AND namespace.nspname NOT LIKE 'pg_toast%'
    AND namespace.nspname NOT LIKE 'pg_temp_%'
    AND (has_table_privilege(current_user,relation.oid,'INSERT')
      OR has_table_privilege(current_user,relation.oid,'UPDATE')
      OR has_table_privilege(current_user,relation.oid,'DELETE')
      OR has_table_privilege(current_user,relation.oid,'TRUNCATE')
      OR has_table_privilege(current_user,relation.oid,'REFERENCES')
      OR has_table_privilege(current_user,relation.oid,'TRIGGER'))
  ORDER BY namespace.nspname,relation.relname")"
if [[ -n "$unexpected_table_writes" ]]; then
  echo "Risk runtime has forbidden business-table write privileges:" >&2
  echo "$unexpected_table_writes" >&2
  exit 1
fi

unexpected_schema_create="$(query "
  SELECT nspname FROM pg_namespace
  WHERE nspname NOT IN ('risk','pg_catalog','information_schema')
    AND nspname NOT LIKE 'pg_toast%'
    AND nspname NOT LIKE 'pg_temp_%'
    AND has_schema_privilege(current_user,oid,'CREATE')
  ORDER BY nspname")"
if [[ -n "$unexpected_schema_create" ]]; then
  echo "Risk runtime can create objects outside risk:" >&2
  echo "$unexpected_schema_create" >&2
  exit 1
fi

risk_write_count="$(query "
  SELECT count(*)
  FROM pg_class relation
  JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
  WHERE namespace.nspname='risk'
    AND relation.relkind IN ('r','p')
    AND (has_table_privilege(current_user,relation.oid,'INSERT')
      OR has_table_privilege(current_user,relation.oid,'UPDATE'))")"
if [[ "$risk_write_count" -lt 1 ]]; then
  echo "Risk runtime has no write access inside schema risk" >&2
  exit 1
fi

echo "RISK_DATABASE_BOUNDARY_OK database=${actual_database} user=${actual_user} writableSchemas=risk"
