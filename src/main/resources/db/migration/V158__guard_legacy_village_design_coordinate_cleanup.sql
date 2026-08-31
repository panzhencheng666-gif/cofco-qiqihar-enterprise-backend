-- Install a guarded, explicitly invoked cleanup contract. Applying this migration
-- never deletes data: the exact legacy cleanup runs only through the owner-only
-- wrapper after its fixed dataset, administrative level, count and code digest pass.

CREATE FUNCTION platform.execute_guarded_legacy_village_coordinate_cleanup(
    p_dataset_sha256 char(64),
    p_expected_count integer,
    p_expected_code_sha256 char(64),
    p_expected_township_count integer,
    p_expected_village_region_count integer,
    p_expected_sample_point_count integer,
    p_expected_sample_point_kind_counts jsonb,
    p_actor_subject_id varchar,
    p_work_unit_code varchar,
    p_authorization_basis varchar,
    p_operation_key varchar
)
RETURNS TABLE(event_id uuid, deleted_count integer, replayed boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, platform, registry
AS $$
DECLARE
    target_count integer;
    target_code_sha256 char(64);
    existing_event_id uuid;
    new_event_id uuid;
    occurred_at timestamptz;
    visible_region_codes varchar(18)[];
    batch_before jsonb;
    batch_after jsonb;
    village_region_count_before bigint;
    village_region_count_after bigint;
    township_location_count_before bigint;
    township_location_count_after bigint;
    sample_point_count_before bigint;
    sample_point_count_after bigint;
    sample_point_kind_counts_before jsonb;
    sample_point_kind_counts_after jsonb;
    event_detail jsonb;
BEGIN
    IF p_dataset_sha256 IS NULL
       OR btrim(p_dataset_sha256) !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'legacy village coordinate dataset digest must be lowercase SHA-256'
            USING ERRCODE='22023';
    END IF;
    IF p_expected_count IS NULL OR p_expected_count <= 0 THEN
        RAISE EXCEPTION 'legacy village coordinate expected count must be positive'
            USING ERRCODE='22023';
    END IF;
    IF p_expected_township_count IS NULL OR p_expected_township_count <= 0
       OR p_expected_village_region_count IS NULL OR p_expected_village_region_count <= 0
       OR p_expected_sample_point_count IS NULL OR p_expected_sample_point_count < 0
       OR p_expected_sample_point_kind_counts IS NULL
       OR jsonb_typeof(p_expected_sample_point_kind_counts)<>'object' THEN
        RAISE EXCEPTION 'legacy village coordinate protected sentinel contract is invalid'
            USING ERRCODE='22023';
    END IF;
    IF p_expected_code_sha256 IS NULL
       OR btrim(p_expected_code_sha256) !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'legacy village coordinate code digest must be lowercase SHA-256'
            USING ERRCODE='22023';
    END IF;
    IF p_authorization_basis IS NULL OR btrim(p_authorization_basis)='' THEN
        RAISE EXCEPTION 'legacy village coordinate cleanup requires authorization basis'
            USING ERRCODE='22023';
    END IF;
    IF length(p_authorization_basis) > 500 THEN
        RAISE EXCEPTION 'legacy village coordinate authorization basis exceeds 500 characters'
            USING ERRCODE='22023';
    END IF;
    IF p_operation_key IS NULL OR btrim(p_operation_key)=''
       OR length(p_operation_key) > 120 THEN
        RAISE EXCEPTION 'legacy village coordinate operation key is invalid'
            USING ERRCODE='22023';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM platform.security_user actor
        WHERE actor.subject_id=p_actor_subject_id
          AND actor.work_unit_code=p_work_unit_code
          AND actor.enabled
    ) THEN
        RAISE EXCEPTION 'legacy village coordinate actor is not enabled in work unit %',
            p_work_unit_code USING ERRCODE='42501';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(p_operation_key,0));

    SELECT count(*)::integer,
           encode(sha256(convert_to(
               string_agg(region.code,E'\n' ORDER BY region.code),'UTF8')),'hex')
    INTO target_count,target_code_sha256
    FROM platform.region_location location
    JOIN platform.region region ON region.code=location.region_code
    WHERE location.dataset_sha256=p_dataset_sha256
      AND region.administrative_level='VILLAGE';

    SELECT audit.event_id
    INTO existing_event_id
    FROM platform.business_audit_event audit
    WHERE audit.aggregate_type='DESIGN_COORDINATE_DATASET'
      AND audit.aggregate_id=p_operation_key
      AND audit.action_code='LEGACY_VILLAGE_DESIGN_COORDINATES_DELETED'
      AND audit.detail->>'dataset_sha256'=btrim(p_dataset_sha256)
      AND audit.detail->>'expected_code_sha256'=btrim(p_expected_code_sha256)
    ORDER BY audit.occurred_at,audit.event_id
    LIMIT 1;

    IF existing_event_id IS NOT NULL THEN
        IF target_count<>0 THEN
            RAISE EXCEPTION
                'legacy village coordinate cleanup receipt exists but % target rows reappeared',
                target_count USING ERRCODE='23514';
        END IF;
        RETURN QUERY SELECT existing_event_id,0,true;
        RETURN;
    END IF;

    IF target_count<>p_expected_count THEN
        RAISE EXCEPTION
            'expected % legacy village coordinate rows for dataset %, found %',
            p_expected_count,btrim(p_dataset_sha256),target_count
            USING ERRCODE='23514';
    END IF;
    IF target_code_sha256 IS DISTINCT FROM p_expected_code_sha256 THEN
        RAISE EXCEPTION
            'legacy village coordinate code digest mismatch: expected %, found %',
            btrim(p_expected_code_sha256),coalesce(btrim(target_code_sha256),'null')
            USING ERRCODE='23514';
    END IF;

    SELECT to_jsonb(batch)
    INTO batch_before
    FROM platform.geography_import_batch batch
    WHERE batch.dataset_sha256=p_dataset_sha256;
    IF batch_before IS NULL THEN
        RAISE EXCEPTION 'legacy village coordinate import lineage is missing'
            USING ERRCODE='23514';
    END IF;

    SELECT count(*) INTO village_region_count_before
    FROM platform.region WHERE administrative_level='VILLAGE';
    SELECT count(*) INTO township_location_count_before
    FROM platform.region_location location
    JOIN platform.region region ON region.code=location.region_code
    WHERE location.dataset_sha256=p_dataset_sha256
      AND region.administrative_level='TOWNSHIP';
    IF village_region_count_before<>p_expected_village_region_count THEN
        RAISE EXCEPTION 'expected % village master rows, found %',
            p_expected_village_region_count,village_region_count_before
            USING ERRCODE='23514';
    END IF;
    IF township_location_count_before<>p_expected_township_count THEN
        RAISE EXCEPTION 'expected % protected township coordinates, found %',
            p_expected_township_count,township_location_count_before
            USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO sample_point_count_before FROM registry.sample_point;
    SELECT coalesce(jsonb_object_agg(kind_code,kind_count),'{}'::jsonb)
    INTO sample_point_kind_counts_before
    FROM (
        SELECT kind_code,count(*) AS kind_count
        FROM registry.sample_point GROUP BY kind_code
    ) kinds;
    IF sample_point_count_before<>p_expected_sample_point_count
       OR sample_point_kind_counts_before IS DISTINCT FROM p_expected_sample_point_kind_counts THEN
        RAISE EXCEPTION 'formal sample sentinel mismatch: expected % / %, found % / %',
            p_expected_sample_point_count,p_expected_sample_point_kind_counts,
            sample_point_count_before,sample_point_kind_counts_before
            USING ERRCODE='23514';
    END IF;
    SELECT array_agg(DISTINCT prefecture.code ORDER BY prefecture.code)
    INTO visible_region_codes
    FROM platform.region_location location
    JOIN platform.region village
      ON village.code=location.region_code
     AND village.administrative_level='VILLAGE'
    JOIN platform.region township
      ON township.code=village.parent_code
     AND township.administrative_level='TOWNSHIP'
    JOIN platform.region county
      ON county.code=township.parent_code
     AND county.administrative_level='COUNTY'
    JOIN platform.region prefecture
      ON prefecture.code=county.parent_code
     AND prefecture.administrative_level='PREFECTURE'
    WHERE location.dataset_sha256=p_dataset_sha256;
    IF visible_region_codes IS NULL OR cardinality(visible_region_codes)=0 THEN
        RAISE EXCEPTION 'legacy village coordinate cleanup has no visible prefecture scope'
            USING ERRCODE='23514';
    END IF;

    DELETE FROM platform.region_location location
    USING platform.region region
    WHERE region.code=location.region_code
      AND region.administrative_level='VILLAGE'
      AND location.dataset_sha256=p_dataset_sha256;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    IF deleted_count<>p_expected_count THEN
        RAISE EXCEPTION 'legacy village coordinate delete count changed during transaction'
            USING ERRCODE='40001';
    END IF;

    SELECT count(*)::integer INTO target_count
    FROM platform.region_location location
    JOIN platform.region region ON region.code=location.region_code
    WHERE location.dataset_sha256=p_dataset_sha256
      AND region.administrative_level='VILLAGE';
    SELECT to_jsonb(batch) INTO batch_after
    FROM platform.geography_import_batch batch
    WHERE batch.dataset_sha256=p_dataset_sha256;
    SELECT count(*) INTO village_region_count_after
    FROM platform.region WHERE administrative_level='VILLAGE';
    SELECT count(*) INTO township_location_count_after
    FROM platform.region_location location
    JOIN platform.region region ON region.code=location.region_code
    WHERE location.dataset_sha256=p_dataset_sha256
      AND region.administrative_level='TOWNSHIP';
    SELECT count(*) INTO sample_point_count_after FROM registry.sample_point;
    SELECT coalesce(jsonb_object_agg(kind_code,kind_count),'{}'::jsonb)
    INTO sample_point_kind_counts_after
    FROM (
        SELECT kind_code,count(*) AS kind_count
        FROM registry.sample_point GROUP BY kind_code
    ) kinds;

    IF target_count<>0
       OR batch_after IS DISTINCT FROM batch_before
       OR village_region_count_after<>village_region_count_before
       OR township_location_count_after<>township_location_count_before
       OR sample_point_count_after<>sample_point_count_before
       OR sample_point_kind_counts_after IS DISTINCT FROM sample_point_kind_counts_before THEN
        RAISE EXCEPTION 'legacy village coordinate protected sentinel changed during cleanup'
            USING ERRCODE='23514';
    END IF;

    new_event_id:=gen_random_uuid();
    occurred_at:=clock_timestamp();
    event_detail:=jsonb_build_object(
        'operation_key',p_operation_key,
        'dataset_sha256',btrim(p_dataset_sha256),
        'administrative_level','VILLAGE',
        'authorization_basis',p_authorization_basis,
        'old_count',p_expected_count,
        'new_count',0,
        'expected_code_sha256',btrim(p_expected_code_sha256),
        'new_code_sha256',NULL,
        'preserved_village_region_count',village_region_count_after,
        'preserved_township_location_count',township_location_count_after,
        'preserved_sample_point_count',sample_point_count_after,
        'preserved_sample_point_kind_counts',sample_point_kind_counts_after,
        'regionCodes',to_jsonb(visible_region_codes));

    INSERT INTO platform.business_audit_event(
        event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
        work_unit_code,occurred_at,detail)
    VALUES(new_event_id,'DESIGN_COORDINATE_DATASET',p_operation_key,
        'LEGACY_VILLAGE_DESIGN_COORDINATES_DELETED',p_actor_subject_id,
        p_work_unit_code,occurred_at,event_detail);
    INSERT INTO platform.business_event_outbox(
        event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
        work_unit_code,region_codes,product_code,occurred_at,detail)
    VALUES(new_event_id,'DESIGN_COORDINATE_DATASET',p_operation_key,
        'LEGACY_VILLAGE_DESIGN_COORDINATES_DELETED',p_actor_subject_id,
        p_work_unit_code,visible_region_codes,NULL,occurred_at,event_detail);

    event_id:=new_event_id;
    replayed:=false;
    RETURN NEXT;
END;
$$;

CREATE FUNCTION platform.cleanup_legacy_village_design_coordinates(
    p_actor_subject_id varchar,
    p_work_unit_code varchar,
    p_authorization_basis varchar
)
RETURNS TABLE(event_id uuid, deleted_count integer, replayed boolean)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, platform
AS $$
    SELECT *
    FROM platform.execute_guarded_legacy_village_coordinate_cleanup(
        'f3cfdaa80b9836514caaa5d496137cce27bf1971fb8b8d5596542d04cbb53799',
        2332,
        '03b176dd2eeeba27422f213b082f6c4121c640baf760d10b24df669370fd4dcd',
        232,
        2332,
        1064,
        '{"SURVEY_SITE": 1062, "LOGISTICS_NODE": 2}'::jsonb,
        p_actor_subject_id,
        p_work_unit_code,
        p_authorization_basis,
        'legacy-village-design-coordinate-cleanup-v1')
$$;

ALTER FUNCTION platform.execute_guarded_legacy_village_coordinate_cleanup(
    char,integer,char,integer,integer,integer,jsonb,varchar,varchar,varchar,varchar)
OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.cleanup_legacy_village_design_coordinates(
    varchar,varchar,varchar)
OWNER TO qiqihar_migration_owner;

REVOKE ALL ON FUNCTION platform.execute_guarded_legacy_village_coordinate_cleanup(
    char,integer,char,integer,integer,integer,jsonb,varchar,varchar,varchar,varchar)
FROM PUBLIC,qiqihar_enterprise_runtime;
REVOKE ALL ON FUNCTION platform.cleanup_legacy_village_design_coordinates(
    varchar,varchar,varchar)
FROM PUBLIC,qiqihar_enterprise_runtime;

GRANT EXECUTE ON FUNCTION platform.cleanup_legacy_village_design_coordinates(
    varchar,varchar,varchar)
TO CURRENT_USER;

COMMENT ON FUNCTION platform.execute_guarded_legacy_village_coordinate_cleanup(
    char,integer,char,integer,integer,integer,jsonb,varchar,varchar,varchar,varchar) IS
    'Owner-only transactional executor used to verify cleanup rollback and idempotency without weakening the fixed business wrapper.';
COMMENT ON FUNCTION platform.cleanup_legacy_village_design_coordinates(
    varchar,varchar,varchar) IS
    'Explicitly invoked fail-closed physical cleanup for the verified legacy VILLAGE coordinate dataset; migration installation alone never deletes data.';
