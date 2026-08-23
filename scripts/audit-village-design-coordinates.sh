#!/bin/sh
set -eu

usage() {
  echo "usage: $0 --database qiqihar_enterprise_dev --output <csv-path>" >&2
  exit 64
}

final_governance_status() {
  case "$1" in
    REVIEWED) printf '%s\n' 'PENDING_AUTHORITY_REVIEW_STATUS_REQUIRES_RECHECK' ;;
    *) printf '%s%s\n' 'PENDING_SPATIAL_AUTHORITY_' "$1" ;;
  esac
}

if [ "${AUDIT_LIBRARY_ONLY:-}" = '1' ]; then
  return 0 2>/dev/null || exit 0
fi

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

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository_root=$(CDPATH= cd -- "$script_directory/.." && pwd -P)
runtime_directory="$repository_root/.local-runtime"
evidence_directory="$runtime_directory/evidence"
output_basename=$(basename -- "$output")

case "$output_basename" in
  [A-Za-z0-9][A-Za-z0-9._-]*.csv) ;;
  *)
    echo 'refusing audit: output requires a safe CSV basename' >&2
    exit 64
    ;;
esac

case "$output" in
  ".local-runtime/evidence/$output_basename"|"$evidence_directory/$output_basename") ;;
  *)
    echo 'refusing audit: output must be in the physical .local-runtime/evidence directory' >&2
    exit 64
    ;;
esac

if [ -L "$runtime_directory" ] || [ -L "$evidence_directory" ]; then
  echo 'refusing audit: .local-runtime/evidence must not be a symlink' >&2
  exit 64
fi
if [ -e "$runtime_directory" ] && [ ! -d "$runtime_directory" ]; then
  echo 'refusing audit: .local-runtime must be a directory' >&2
  exit 64
fi
mkdir -p -- "$evidence_directory"
if [ -L "$evidence_directory" ] || [ "$(CDPATH= cd -- "$evidence_directory" && pwd -P)" != "$evidence_directory" ]; then
  echo 'refusing audit: .local-runtime/evidence must be a physical directory' >&2
  exit 64
fi
output_relative=".local-runtime/evidence/$output_basename"
if git -C "$repository_root" ls-files --error-unmatch -- "$output_relative" >/dev/null 2>&1; then
  echo 'refusing audit: output path is tracked by Git' >&2
  exit 64
fi
final_output="$evidence_directory/$output_basename"

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
WITH village AS (
  SELECT village.code AS village_code,
         prefecture.code AS prefecture_code,
         location.source_name,
         location.source_revision,
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
)
SELECT concat_ws('|',
  (SELECT count(*) FROM platform.region WHERE administrative_level='VILLAGE'),
  (SELECT count(*)
     FROM platform.region village
     JOIN platform.region_location location ON location.region_code=village.code
    WHERE village.administrative_level='VILLAGE'),
  (SELECT count(DISTINCT code) FROM platform.region WHERE administrative_level='VILLAGE'),
  count(*) FILTER (WHERE prefecture_code='150700'),
  count(*) FILTER (WHERE prefecture_code='230200'),
  count(*) FILTER (WHERE prefecture_code='231100'),
  count(*) FILTER (WHERE source_name='中国·国家地名信息库'
                    AND source_revision='2025-12-31'),
  count(*) FILTER (WHERE dataset_sha256='f3cfdaa80b9836514caaa5d496137cce27bf1971fb8b8d5596542d04cbb53799'
                    AND source_workbook_sha256='dea2df64a09e9fe8718f415cd58c860a462d0e0b6692ade46b8a4f64b0ee4264')
)
FROM village;
COMMIT;" | psql_readonly --no-align --tuples-only)

[ "$counts" = '2332|2332|2332|571|1197|564|2332|2332' ] || {
  echo "refusing audit: approved three-prefecture source identity did not match; got ${counts:-no-result}" >&2
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
       COALESCE(NULLIF(concat_ws(';',
         CASE WHEN village.official_place_code IS NULL
                    OR length(btrim(village.official_place_code)) <> 20
           THEN 'MISSING_OR_INVALID_OFFICIAL_PLACE_CODE' END,
         CASE WHEN village.official_area_code IS NULL
                    OR length(btrim(village.official_area_code)) <> 9
           THEN 'MISSING_OR_INVALID_OFFICIAL_AREA_CODE' END,
         CASE WHEN village.official_area_code IS NOT NULL
                    AND length(btrim(village.official_area_code)) = 9
                    AND btrim(village.official_area_code) <> village.township_code
           THEN 'OFFICIAL_AREA_CODE_TOWNSHIP_MISMATCH' END,
         CASE WHEN village.official_place_code IS NOT NULL
                    AND length(btrim(village.official_place_code)) = 20
                    AND village.official_area_code IS NOT NULL
                    AND length(btrim(village.official_area_code)) = 9
                    AND left(btrim(village.official_place_code), 9)
                        <> btrim(village.official_area_code)
           THEN 'OFFICIAL_PLACE_AREA_PREFIX_MISMATCH' END,
         CASE WHEN village.official_standard_name IS NULL
                    OR btrim(village.official_standard_name) = ''
           THEN 'MISSING_OFFICIAL_STANDARD_NAME' END,
         CASE WHEN village.official_standard_name IS NOT NULL
                    AND btrim(village.official_standard_name) <> btrim(village.village_name)
           THEN 'OFFICIAL_NAME_VARIANT_OR_MISMATCH' END,
         CASE WHEN village.place_type NOT IN ('行政村', 'VILLAGE', 'Administrative Village')
           THEN 'NON_ADMINISTRATIVE_PLACE_TYPE' END
       ), ''), 'NONE') AS hierarchy_name_anomaly,
       'PENDING_AUTHORITY_NO_AUTHORITATIVE_PREFECTURE_COUNTY_TOWNSHIP_POLYGONS'
         AS spatial_authority_status,
       CASE WHEN village.review_status='REVIEWED'
              THEN 'PENDING_AUTHORITY_REVIEW_STATUS_REQUIRES_RECHECK'
            ELSE 'PENDING_SPATIAL_AUTHORITY_' || village.review_status END
         AS final_governance_status,
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
if awk -F',' 'NR > 1 && $30 == "REVIEWED" { found=1 } END { exit(found ? 0 : 1) }' "$temporary_output"; then
  echo 'refusing audit: CSV would promote a row to REVIEWED' >&2
  exit 1
fi

printf '%s\n%s\n' "$assert_readonly_session" "
WITH village AS (
  SELECT location.source_name, location.source_revision, location.review_status,
         location.original_coordinate,
         village.code AS village_code,
         COALESCE(NULLIF(concat_ws(';',
           CASE WHEN location.official_place_code IS NULL
                      OR length(btrim(location.official_place_code)) <> 20
             THEN 'MISSING_OR_INVALID_OFFICIAL_PLACE_CODE' END,
           CASE WHEN location.official_area_code IS NULL
                      OR length(btrim(location.official_area_code)) <> 9
             THEN 'MISSING_OR_INVALID_OFFICIAL_AREA_CODE' END,
           CASE WHEN location.official_area_code IS NOT NULL
                      AND length(btrim(location.official_area_code)) = 9
                      AND btrim(location.official_area_code) <> village.parent_code
             THEN 'OFFICIAL_AREA_CODE_TOWNSHIP_MISMATCH' END,
           CASE WHEN location.official_place_code IS NOT NULL
                      AND length(btrim(location.official_place_code)) = 20
                      AND location.official_area_code IS NOT NULL
                      AND length(btrim(location.official_area_code)) = 9
                      AND left(btrim(location.official_place_code), 9)
                          <> btrim(location.official_area_code)
             THEN 'OFFICIAL_PLACE_AREA_PREFIX_MISMATCH' END,
           CASE WHEN location.official_standard_name IS NULL
                      OR btrim(location.official_standard_name) = ''
             THEN 'MISSING_OFFICIAL_STANDARD_NAME' END,
           CASE WHEN location.official_standard_name IS NOT NULL
                      AND btrim(location.official_standard_name) <> btrim(village.name)
             THEN 'OFFICIAL_NAME_VARIANT_OR_MISMATCH' END,
           CASE WHEN location.place_type NOT IN ('行政村', 'VILLAGE', 'Administrative Village')
             THEN 'NON_ADMINISTRATIVE_PLACE_TYPE' END
         ), ''), 'NONE') AS hierarchy_name_anomaly
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

mv -- "$temporary_output" "$final_output"
trap - EXIT HUP INT TERM
echo "AUDIT_CSV_WRITTEN $output_relative rows=$data_rows"
