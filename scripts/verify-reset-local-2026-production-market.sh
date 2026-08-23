#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
reset_script="$script_directory/reset-local-2026-production-market.sh"
reset_sql="$script_directory/../ops/reset_local_2026_production_market.sql"
verification_temp=$(mktemp -d "${TMPDIR:-/tmp}/cofco-reset-verify.XXXXXX")
trap 'rm -rf -- "$verification_temp"' EXIT HUP INT TERM

snapshot() {
  psql --host=127.0.0.1 \
    --port=5432 \
    --dbname=qiqihar_enterprise_dev \
    --no-align \
    --tuples-only \
    --set=ON_ERROR_STOP=1 <<'SQL'
SELECT concat_ws('|',
  current_database(),
  inet_server_addr(),
  (SELECT count(*) FROM production.production_record
   WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN')),
  (SELECT count(*) FROM market.market_record
   WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN')),
  (SELECT count(*) FROM platform.business_import_draft
   WHERE domain_code IN ('PRODUCTION','MARKET')
     AND product_code IN ('CORN','RICE','SOYBEAN')
     AND survey_period LIKE '2026%'),
  (SELECT count(*) FROM platform.region WHERE administrative_level='VILLAGE'),
  (SELECT count(*) FROM platform.region village
   JOIN platform.region_location location ON location.region_code=village.code
   WHERE village.administrative_level='VILLAGE'),
  (SELECT count(*) FROM registry.sample_point),
  (SELECT count(*) FROM platform.import_job),
  (SELECT count(*) FROM platform.security_user),
  (SELECT count(*) FROM platform.business_audit_event),
  (SELECT count(*) FROM reporting.report_audit_event));
SQL
}

before_snapshot=$(snapshot)

if psql --dbname=qiqihar_enterprise_dev \
  --set=ON_ERROR_STOP=1 --set=apply=false --set=expected_digest='' \
  --file="$reset_sql" >"$verification_temp/socket-guard.log" 2>&1; then
  echo "verification failed: Unix-socket connection bypassed numeric loopback guard" >&2
  exit 1
fi
if ! grep -q 'numeric loopback server 127.0.0.1' "$verification_temp/socket-guard.log"; then
  echo "verification failed: Unix-socket rejection did not come from loopback guard" >&2
  exit 1
fi

if psql --host=127.0.0.1 --port=5432 --dbname=qiqihar_enterprise_test \
  --set=ON_ERROR_STOP=1 --set=apply=false --set=expected_digest='' \
  --file="$reset_sql" >"$verification_temp/database-guard.log" 2>&1; then
  echo "verification failed: test database bypassed exact database guard" >&2
  exit 1
fi
if ! grep -q 'exact database qiqihar_enterprise_dev' "$verification_temp/database-guard.log"; then
  echo "verification failed: wrong-database rejection did not come from database guard" >&2
  exit 1
fi

"$reset_script" preview >"$verification_temp/preview-before.log"

if ! grep -q '^ROLLBACK$' "$verification_temp/preview-before.log"; then
  echo "verification failed: preview did not roll back" >&2
  exit 1
fi

preview_digest=$(sed -nE 's/^[[:space:]]*([0-9a-f]{64})[[:space:]]*$/\1/p' \
  "$verification_temp/preview-before.log" | tail -n 1)
if [ -z "$preview_digest" ]; then
  echo "verification failed: preview digest missing" >&2
  exit 1
fi

wrong_digest=0000000000000000000000000000000000000000000000000000000000000000
if [ "$preview_digest" = "$wrong_digest" ]; then
  wrong_digest=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
fi
if "$reset_script" apply "$wrong_digest" >"$verification_temp/wrong-digest.log" 2>&1; then
  echo "verification failed: incorrect digest was accepted" >&2
  exit 1
fi
if ! grep -q 'reset digest mismatch' "$verification_temp/wrong-digest.log"; then
  echo "verification failed: incorrect digest did not fail at the digest guard" >&2
  exit 1
fi

"$reset_script" preview >"$verification_temp/preview-after.log"
after_digest=$(sed -nE 's/^[[:space:]]*([0-9a-f]{64})[[:space:]]*$/\1/p' \
  "$verification_temp/preview-after.log" | tail -n 1)
after_snapshot=$(snapshot)

if [ "$preview_digest" != "$after_digest" ]; then
  echo "verification failed: manifest changed after rejected apply" >&2
  exit 1
fi
if [ "$before_snapshot" != "$after_snapshot" ]; then
  echo "verification failed: database sentinels changed after preview/rejected apply" >&2
  exit 1
fi

echo "RESET_GUARDS_VERIFIED digest=$preview_digest"
echo "DATABASE_SENTINELS_UNCHANGED $after_snapshot"
