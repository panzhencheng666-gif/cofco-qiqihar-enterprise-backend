#!/bin/sh
set -eu

usage() {
  echo "usage: $0 --database qiqihar_enterprise_dev --output <csv-path>" >&2
  exit 64
}

database=''
output=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --database)
      [ "$#" -ge 2 ] || usage
      database=$2
      shift 2
      ;;
    --output)
      [ "$#" -ge 2 ] || usage
      output=$2
      shift 2
      ;;
    *) usage ;;
  esac
done

[ "$database" = 'qiqihar_enterprise_dev' ] || {
  echo 'refusing audit: exact database qiqihar_enterprise_dev is required' >&2
  exit 64
}
[ -n "$output" ] || usage

output_directory=$(dirname -- "$output")
mkdir -p -- "$output_directory"

psql_readonly() {
  psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 \
    --host=127.0.0.1 --port=5432 --dbname="$database" "$@"
}

assert_readonly_session='BEGIN TRANSACTION READ ONLY;
DO $$
BEGIN
  IF current_database() <> '\''qiqihar_enterprise_dev'\'' THEN
    RAISE EXCEPTION '\''audit requires exact database qiqihar_enterprise_dev'\'';
  END IF;
  IF inet_server_addr() <> '\''127.0.0.1'\''::inet OR inet_server_port() <> 5432 THEN
    RAISE EXCEPTION '\''audit requires numeric loopback server 127.0.0.1:5432'\'';
  END IF;
  IF current_setting('\''transaction_read_only'\'') <> '\''on'\'' THEN
    RAISE EXCEPTION '\''audit requires transaction_read_only=on'\'';
  END IF;
END
$$;'

counts=$(printf '%s\n%s\n%s\n' "$assert_readonly_session" "
SELECT concat_ws('|',
  (SELECT count(*) FROM platform.region WHERE administrative_level='VILLAGE'),
  (SELECT count(*)
     FROM platform.region village
     JOIN platform.region_location location ON location.region_code=village.code
    WHERE village.administrative_level='VILLAGE'),
  (SELECT count(DISTINCT code) FROM platform.region WHERE administrative_level='VILLAGE'));
COMMIT;" | psql_readonly --no-align --tuples-only)

[ "$counts" = '2332|2332|2332' ] || {
  echo "refusing audit: expected 2332 villages, location rows, and unique village codes; got ${counts:-no-result}" >&2
  exit 1
}

temporary_output=$(mktemp "${TMPDIR:-/tmp}/cofco-village-design-audit.XXXXXX")
trap 'rm -f -- "$temporary_output"' EXIT HUP INT TERM

printf '%s\n%s\n' "$assert_readonly_session" "
WITH village AS (
  SELECT village.code AS village_code,
         village.name AS village_name,
         township.code AS township_code,
         township.name AS township_name,
         county.code AS county_code,
         county.name AS county_name,
         prefecture.code AS prefecture_code,
         prefecture.name AS prefecture_name,
         location.original_coordinate,
         location.wgs84_coordinate,
         location.source_name,
         location.source_url,
         location.source_revision,
         location.official_place_id,
         location.official_place_code,
         location.official_standard_name,
         location.official_area_code,
         location.place_type,
         location.matched_by,
         location.match_confidence,
         location.review_status,
         location.review_note,
         location.dataset_sha256,
         batch.source_workbook_sha256
    FROM platform.region village
    JOIN platform.region township
      ON township.code=village.parent_code
     AND township.administrative_level='TOWNSHIP'
    JOIN platform.region county
      ON county.code=township.parent_code
     AND county.administrative_level='COUNTY'
    JOIN platform.region prefecture
      ON prefecture.code=county.parent_code
     AND prefecture.administrative_level='PREFECTURE'
    JOIN platform.region_location location ON location.region_code=village.code
    JOIN platform.geography_import_batch batch
      ON batch.dataset_sha256=location.dataset_sha256
   WHERE village.administrative_level='VILLAGE'
), coordinate_duplicates AS (
  SELECT ST_AsEWKB(original_coordinate) AS coordinate_key,
         count(*) AS member_count,
         string_agg(village_code, ';' ORDER BY village_code) AS member_codes
    FROM village
   GROUP BY ST_AsEWKB(original_coordinate)
  HAVING count(*) > 1
)
SELECT village.village_code,
       village.village_name,
       village.township_code,
       village.township_name,
       village.county_code,
       village.county_name,
       village.prefecture_code,
       village.prefecture_name,
       ST_AsEWKT(village.original_coordinate) AS original_coordinate_ewkt,
       ST_AsEWKT(village.wgs84_coordinate) AS wgs84_coordinate_ewkt,
       ST_X(village.wgs84_coordinate)::text AS longitude,
       ST_Y(village.wgs84_coordinate)::text AS latitude,
       village.source_name,
       village.source_url,
       village.source_revision,
       village.dataset_sha256,
       village.source_workbook_sha256,
       village.official_place_id,
       village.official_place_code,
       village.official_standard_name,
       village.official_area_code,
       village.place_type,
       village.matched_by,
       village.match_confidence,
       village.review_status AS stored_review_status,
       COALESCE(duplicate.member_count, 1) AS duplicate_coordinate_group_size,
       COALESCE(duplicate.member_codes, village.village_code) AS duplicate_coordinate_member_codes,
       CASE
         WHEN village.review_status LIKE '%HIERARCHY%' THEN village.review_status
         WHEN village.review_status LIKE '%NAME%' THEN village.review_status
         WHEN village.place_type NOT IN ('行政村', 'VILLAGE', 'Administrative Village')
           THEN 'NON_ADMINISTRATIVE_PLACE_TYPE'
         WHEN village.official_standard_name IS NULL THEN 'MISSING_OFFICIAL_STANDARD_NAME'
         WHEN btrim(village.official_standard_name) <> btrim(village.village_name)
           THEN 'OFFICIAL_NAME_VARIANT_OR_MISMATCH'
         ELSE 'NONE'
       END AS hierarchy_name_anomaly,
       'PENDING_AUTHORITY_NO_AUTHORITATIVE_PREFECTURE_COUNTY_TOWNSHIP_POLYGONS'
         AS spatial_authority_status,
       CASE
         WHEN village.review_status='REVIEWED'
           THEN 'PENDING_AUTHORITY_REVIEW_STATUS_REQUIRES_RECHECK'
         ELSE 'PENDING_SPATIAL_AUTHORITY_' || village.review_status
       END AS final_governance_status,
       village.review_note
  FROM village
  LEFT JOIN coordinate_duplicates duplicate
    ON duplicate.coordinate_key=ST_AsEWKB(village.original_coordinate)
 ORDER BY village.prefecture_code, village.county_code, village.township_code, village.village_code;
COMMIT;" | psql_readonly --csv >"$temporary_output"

data_rows=$(awk 'END { print NR - 1 }' "$temporary_output")
[ "$data_rows" = '2332' ] || {
  echo "refusing audit: expected 2332 CSV data rows; got $data_rows" >&2
  exit 1
}
if grep -Eq '(^|,)REVIEWED(,|$)' "$temporary_output"; then
  echo 'refusing audit: CSV would promote a row to REVIEWED' >&2
  exit 1
fi
mv -- "$temporary_output" "$output"
trap - EXIT HUP INT TERM

printf '%s\n%s\n' "$assert_readonly_session" "
WITH village AS (
  SELECT location.source_name, location.source_revision, location.review_status,
         location.original_coordinate,
         village.code AS village_code,
         CASE
           WHEN location.review_status LIKE '%HIERARCHY%' THEN location.review_status
           WHEN location.review_status LIKE '%NAME%' THEN location.review_status
           WHEN location.place_type NOT IN ('行政村', 'VILLAGE', 'Administrative Village')
             THEN 'NON_ADMINISTRATIVE_PLACE_TYPE'
           WHEN location.official_standard_name IS NULL THEN 'MISSING_OFFICIAL_STANDARD_NAME'
           WHEN btrim(location.official_standard_name) <> btrim(village.name)
             THEN 'OFFICIAL_NAME_VARIANT_OR_MISMATCH'
           ELSE 'NONE'
         END AS hierarchy_name_anomaly
    FROM platform.region village
    JOIN platform.region_location location ON location.region_code=village.code
   WHERE village.administrative_level='VILLAGE'
), duplicate_groups AS (
  SELECT ST_AsEWKB(original_coordinate) AS coordinate_key, count(*) AS member_count
    FROM village
   GROUP BY ST_AsEWKB(original_coordinate)
  HAVING count(*) > 1
)
SELECT 'SOURCE_REVISION_COUNT|' || source_name || '|' || source_revision || '|' || count(*)
  FROM village
 GROUP BY source_name, source_revision
UNION ALL
SELECT 'REVIEW_STATUS_COUNT|' || review_status || '|' || count(*)
  FROM village
 GROUP BY review_status
UNION ALL
SELECT 'DUPLICATE_COORDINATE_GROUPS|' || count(*) || '|MEMBERS|' || COALESCE(sum(member_count), 0)
  FROM duplicate_groups
UNION ALL
SELECT 'MISSING_LOCATION_ROWS|' || (
  SELECT count(*)
    FROM platform.region village
    LEFT JOIN platform.region_location location ON location.region_code=village.code
   WHERE village.administrative_level='VILLAGE' AND location.region_code IS NULL)
UNION ALL
SELECT 'HIERARCHY_NAME_ANOMALY_COUNT|' || hierarchy_name_anomaly || '|' || count(*)
  FROM village
 GROUP BY hierarchy_name_anomaly
ORDER BY 1;
COMMIT;" | psql_readonly --no-align --tuples-only

echo "AUDIT_CSV_WRITTEN $output rows=$data_rows"
