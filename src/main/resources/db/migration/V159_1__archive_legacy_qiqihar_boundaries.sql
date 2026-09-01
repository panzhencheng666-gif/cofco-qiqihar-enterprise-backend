-- V160 deliberately refuses to overwrite any existing Qiqihar boundary. The
-- live V153 history contains one exact, previously loaded community dataset,
-- so preserve that known dataset before V160 installs the pinned open-data
-- replacement. Any partial, changed, or unknown boundary state still fails
-- closed and must be reviewed separately.

CREATE TABLE overview.administrative_boundary_v160_archive (
    LIKE overview.administrative_boundary INCLUDING ALL,
    archived_at timestamptz NOT NULL DEFAULT now(),
    archive_reason text NOT NULL,
    replacement_dataset_id varchar(120) NOT NULL
);

DO $$
DECLARE
    qiqihar_codes constant text[] := ARRAY[
        '230200','230202','230203','230204','230205','230206','230207','230208',
        '230221','230223','230224','230225','230227','230229','230230','230231','230281'
    ];
    boundary_count integer;
    invalid_count integer;
    legacy_snapshot_sha256 text;
    archived_count integer;
    deleted_count integer;
BEGIN
    SELECT count(*)
      INTO boundary_count
      FROM overview.administrative_boundary
     WHERE region_code=ANY(qiqihar_codes);

    IF boundary_count=0 THEN
        RETURN;
    END IF;

    SELECT count(*) FILTER (
               WHERE GeometryType(geometry)<>'MULTIPOLYGON'
                  OR NOT ST_IsValid(geometry)
                  OR ST_IsEmpty(geometry)
                  OR ST_SRID(geometry)<>4326
                  OR geometry_sha256<>encode(sha256(ST_AsEWKB(geometry)),'hex')),
           encode(sha256(convert_to(string_agg(
               region_code || chr(31) || source_name || chr(31) || source_url || chr(31) ||
               source_revision || chr(31) || source_license || chr(31) ||
               coalesce(source_feature_id,'') || chr(31) ||
               coalesce(source_effective_on::text,'') || chr(31) || geometry_sha256,
               chr(30) ORDER BY region_code), 'UTF8')), 'hex')
      INTO invalid_count,legacy_snapshot_sha256
      FROM overview.administrative_boundary
     WHERE region_code=ANY(qiqihar_codes);

    IF boundary_count<>17 OR invalid_count<>0
       OR legacy_snapshot_sha256<>'77b3b663eab8cb910b7aa8d7669215a8167040b9f242a75778fa88ad160dc689' THEN
        RAISE EXCEPTION
          'V159.1 refuses unknown Qiqihar boundary state: count %, invalid %, snapshot %',
          boundary_count,invalid_count,legacy_snapshot_sha256;
    END IF;

    INSERT INTO overview.administrative_boundary_v160_archive(
        region_code,geometry,source_name,source_url,source_revision,source_license,
        source_feature_id,source_effective_on,geometry_sha256,loaded_at,
        archive_reason,replacement_dataset_id)
    SELECT region_code,geometry,source_name,source_url,source_revision,source_license,
           source_feature_id,source_effective_on,geometry_sha256,loaded_at,
           'Archived exact live V153 community boundary snapshot before pinned V160 replacement',
           'geoboundaries-chn-adm3-9469f09-qiqihar-2017'
      FROM overview.administrative_boundary
     WHERE region_code=ANY(qiqihar_codes);
    GET DIAGNOSTICS archived_count=ROW_COUNT;

    DELETE FROM overview.administrative_boundary
     WHERE region_code=ANY(qiqihar_codes);
    GET DIAGNOSTICS deleted_count=ROW_COUNT;

    IF archived_count<>17 OR deleted_count<>17 THEN
        RAISE EXCEPTION
          'V159.1 Qiqihar boundary archive was incomplete: archived %, deleted %',
          archived_count,deleted_count;
    END IF;
END;
$$;

ALTER TABLE overview.administrative_boundary_v160_archive
    OWNER TO qiqihar_migration_owner;
REVOKE ALL ON TABLE overview.administrative_boundary_v160_archive FROM PUBLIC;
REVOKE ALL ON TABLE overview.administrative_boundary_v160_archive
    FROM qiqihar_enterprise_runtime;

COMMENT ON TABLE overview.administrative_boundary_v160_archive IS
    'Immutable provenance snapshot of the exact legacy Qiqihar boundary rows replaced by V160; empty on fresh installations.';
