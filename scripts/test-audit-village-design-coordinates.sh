#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
audit_script="$script_directory/audit-village-design-coordinates.sh"
final_status_fragment="$script_directory/sql/village-design-final-status.sql"
verification_temp=$(mktemp -d "${TMPDIR:-/tmp}/cofco-village-design-audit.XXXXXX")
audit_output=.local-runtime/evidence/task6a-focused-audit-test.csv
trap 'rm -rf -- "$verification_temp"; rm -f -- "$audit_output"' EXIT HUP INT TERM

expect_rejection() {
  description=$1
  expected_message=$2
  shift 2

  if "$audit_script" "$@" >"$verification_temp/$description.out" 2>"$verification_temp/$description.err"; then
    echo "verification failed: $description was accepted" >&2
    exit 1
  fi
  if ! grep -Fq "$expected_message" "$verification_temp/$description.err"; then
    echo "verification failed: $description did not report its guard" >&2
    exit 1
  fi
}

expect_rejection \
  database-guard \
  'exact database qiqihar_enterprise_dev' \
  --database qiqihar_enterprise_test \
  --output "$verification_temp/should-not-exist.csv"

expect_rejection \
  missing-output \
  'usage:' \
  --database qiqihar_enterprise_dev

expect_rejection \
  external-output \
  'physical .local-runtime/evidence directory' \
  --database qiqihar_enterprise_dev \
  --output "$verification_temp/outside.csv"

expect_rejection \
  unsafe-basename \
  'safe CSV basename' \
  --database qiqihar_enterprise_dev \
  --output .local-runtime/evidence/task6a-test-invalid.txt

expect_rejection \
  whitespace-basename \
  'safe CSV basename' \
  --database qiqihar_enterprise_dev \
  --output '.local-runtime/evidence/task 6a.csv'

[ ! -e "$verification_temp/outside.csv" ] || {
  echo 'verification failed: rejected external output left an evidence file' >&2
  exit 1
}

fixture_expression=$(sed 's/__STORED_REVIEW_STATUS__/fixture.stored_review_status/g' \
  "$final_status_fragment")
fixture_status=$(psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
  --host=127.0.0.1 --port=5432 --dbname=qiqihar_enterprise_dev \
  --no-align --tuples-only <<SQL
BEGIN TRANSACTION READ ONLY;
WITH fixture(stored_review_status) AS (VALUES ('REVIEWED'))
SELECT ${fixture_expression}
FROM fixture;
COMMIT;
SQL
)
[ "$fixture_status" = 'PENDING_AUTHORITY_REVIEW_STATUS_REQUIRES_RECHECK' ] || {
  echo 'verification failed: stored REVIEWED fixture was not transformed to final pending' >&2
  exit 1
}

snapshot() {
  psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
    --host=127.0.0.1 --port=5432 --dbname=qiqihar_enterprise_dev \
    --no-align --tuples-only <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT concat_ws('|',
  current_database(),
  inet_server_addr(),
  inet_server_port(),
  current_setting('transaction_read_only'),
  (SELECT count(*) FROM platform.region WHERE administrative_level='VILLAGE'),
  (SELECT count(*)
     FROM platform.region village
     JOIN platform.region_location location ON location.region_code=village.code
    WHERE village.administrative_level='VILLAGE'),
  (SELECT count(*)
     FROM platform.region_location location
     JOIN platform.region village ON village.code=location.region_code
    WHERE village.administrative_level='VILLAGE' AND location.review_status='REVIEWED'),
  (SELECT md5(string_agg(
       village.code || ':' || ST_AsEWKT(location.original_coordinate) || ':' || location.review_status,
       ',' ORDER BY village.code))
     FROM platform.region village
     JOIN platform.region_location location ON location.region_code=village.code
    WHERE village.administrative_level='VILLAGE'));
COMMIT;
SQL
}

before_snapshot=$(snapshot)
PGHOST=localhost "$audit_script" \
  --database qiqihar_enterprise_dev \
  --output "$audit_output" >"$verification_temp/audit.out"
after_snapshot=$(snapshot)

[ "$before_snapshot" = "$after_snapshot" ] || {
  echo 'verification failed: audit changed database sentinels' >&2
  exit 1
}
[ "$(awk 'END { print NR }' "$audit_output")" = '2333' ] || {
  echo 'verification failed: audit CSV did not contain header plus 2332 rows' >&2
  exit 1
}
head -n 1 "$audit_output" | grep -Fq 'final_governance_status,village_code,village_name,' || {
  echo 'verification failed: audit CSV does not put final status in the first column' >&2
  exit 1
}
if grep -Eq '^REVIEWED,' "$audit_output"; then
  echo 'verification failed: audit promoted a coordinate to REVIEWED' >&2
  exit 1
fi
grep -Fq 'PENDING_AUTHORITY_NO_AUTHORITATIVE_PREFECTURE_COUNTY_TOWNSHIP_POLYGONS' \
  "$audit_output" || {
  echo 'verification failed: audit did not fail closed on spatial authority' >&2
  exit 1
}
grep -Fq -- "--host=127.0.0.1 --port=5432" "$audit_script" || {
  echo 'verification failed: audit does not force numeric loopback' >&2
  exit 1
}
grep -Fq 'transaction_read_only' "$audit_script" || {
  echo 'verification failed: audit does not assert transaction read-only' >&2
  exit 1
}
if grep -Fq 'AUDIT_LIBRARY_ONLY' "$audit_script"; then
  echo 'verification failed: audit retains a success bypass' >&2
  exit 1
fi
grep -Fq 'village-design-final-status.sql' "$audit_script" || {
  echo 'verification failed: audit does not consume the shared final-status SQL' >&2
  exit 1
}
grep -Fq 'SOURCE_REVISION_COUNT|中国·国家地名信息库|2025-12-31|2332' \
  "$verification_temp/audit.out" || {
  echo 'verification failed: audit did not prove the approved source and revision' >&2
  exit 1
}
grep -Fq 'MISSING_LOCATION_ROWS|0' "$verification_temp/audit.out" || {
  echo 'verification failed: audit did not report missing village locations' >&2
  exit 1
}

echo "AUDIT_ARGUMENT_GUARDS_VERIFIED"
echo "AUDIT_2332_ROWS_READ_ONLY_AND_SENTINELS_UNCHANGED $after_snapshot"
