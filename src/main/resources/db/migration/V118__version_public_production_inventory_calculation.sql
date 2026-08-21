-- DEF-153/155: retain public production opening/ending inventory while
-- versioning the region-surplus calculation that consumes it. V1 remains the
-- immutable historical contract. V2 is registered as a candidate but has no
-- guessed effective date: an audited activation is a separate atomic action.

-- Fail closed before creating any V118 object. The legacy canonical identity
-- and append-only current resolution are separate projections, so neither
-- table's local uniqueness constraints can validate their combined state.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM (
      SELECT legacy.business_domain,legacy.subject_id stable_subject_id,
             legacy.sample_point_id
      FROM registry.sample_point_subject_identity legacy
      UNION ALL
      SELECT current_resolution.source_domain,current_resolution.stable_subject_id,
             current_resolution.target_sample_point_id
      FROM registry.current_sample_subject_resolution current_resolution
      WHERE current_resolution.resolution_action='LINK'
    ) combined
    GROUP BY combined.business_domain,combined.stable_subject_id
    HAVING count(DISTINCT combined.sample_point_id)>1
  ) THEN
    RAISE EXCEPTION 'V118 cross-projection identity conflict: one stable subject maps to multiple sample points';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM (
      SELECT legacy.business_domain,legacy.subject_id stable_subject_id,
             legacy.sample_point_id
      FROM registry.sample_point_subject_identity legacy
      UNION ALL
      SELECT current_resolution.source_domain,current_resolution.stable_subject_id,
             current_resolution.target_sample_point_id
      FROM registry.current_sample_subject_resolution current_resolution
      WHERE current_resolution.resolution_action='LINK'
    ) combined
    GROUP BY combined.business_domain,combined.sample_point_id
    HAVING count(DISTINCT combined.stable_subject_id)>1
  ) THEN
    RAISE EXCEPTION 'V118 cross-projection identity conflict: one sample point maps to multiple stable subjects';
  END IF;
END;
$$;

-- All legacy and append-only identity writers delegate lock naming and global
-- ordering to this single closed helper. Domain is part of every key, keeping
-- MARKET and PRODUCTION mutually isolated while serializing both dimensions.
CREATE FUNCTION registry.lock_sample_subject_identity_keys(
  p_source_domain varchar,p_stable_subject_ids varchar[],p_target_sample_point_ids uuid[])
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,registry
AS $$
DECLARE
  identity_lock_key text;
BEGIN
  IF p_source_domain NOT IN ('PRODUCTION','MARKET') THEN
    RAISE EXCEPTION 'unsupported subject source domain';
  END IF;
  FOR identity_lock_key IN
    SELECT lock_key
    FROM (
      SELECT p_source_domain || ':STABLE_SUBJECT:' || stable_subject_id lock_key
      FROM unnest(coalesce(p_stable_subject_ids,ARRAY[]::varchar[])) stable_subject_id
      UNION
      SELECT p_source_domain || ':TARGET_SAMPLE_POINT:' || target_sample_point_id::text
      FROM unnest(coalesce(p_target_sample_point_ids,ARRAY[]::uuid[])) target_sample_point_id
    ) identity_lock
    WHERE lock_key IS NOT NULL
    ORDER BY lock_key
  LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(identity_lock_key,0));
  END LOOP;
END;
$$;
ALTER FUNCTION registry.lock_sample_subject_identity_keys(varchar,varchar[],uuid[])
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.lock_sample_subject_identity_keys(varchar,varchar[],uuid[])
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
  qiqihar_master_data_reviewer,qiqihar_master_data_applier;

-- The protected legacy table is the single convergence point for every
-- governed SUBJECT writer, including the public three-role V111 workflow.
-- Lock both the old and new identity dimensions before any cross-projection
-- read so INSERT, UPDATE and DELETE serialize with append-only resolutions.
CREATE FUNCTION registry.guard_sample_point_subject_identity_consistency()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,registry
AS $$
DECLARE
  identity_domain_value varchar(30);
BEGIN
  FOR identity_domain_value IN
    SELECT DISTINCT identity_domain
    FROM (VALUES
      (CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN OLD.business_domain END),
      (CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN NEW.business_domain END)
    ) identity_domains(identity_domain)
    WHERE identity_domain IS NOT NULL
    ORDER BY identity_domain
  LOOP
    PERFORM registry.lock_sample_subject_identity_keys(
      identity_domain_value,
      ARRAY(
        SELECT DISTINCT stable_subject_id
        FROM (VALUES
          (CASE WHEN TG_OP IN ('UPDATE','DELETE')
                   AND OLD.business_domain=identity_domain_value THEN OLD.subject_id END),
          (CASE WHEN TG_OP IN ('INSERT','UPDATE')
                   AND NEW.business_domain=identity_domain_value THEN NEW.subject_id END)
        ) stable_subjects(stable_subject_id)
        WHERE stable_subject_id IS NOT NULL
        ORDER BY stable_subject_id),
      ARRAY(
        SELECT DISTINCT target_sample_point_id
        FROM (VALUES
          (CASE WHEN TG_OP IN ('UPDATE','DELETE')
                   AND OLD.business_domain=identity_domain_value THEN OLD.sample_point_id END),
          (CASE WHEN TG_OP IN ('INSERT','UPDATE')
                   AND NEW.business_domain=identity_domain_value THEN NEW.sample_point_id END)
        ) target_sample_points(target_sample_point_id)
        WHERE target_sample_point_id IS NOT NULL
        ORDER BY target_sample_point_id));
  END LOOP;

  IF TG_OP<>'DELETE' THEN
    IF EXISTS (
      SELECT 1
      FROM registry.sample_point_subject_identity legacy
      WHERE legacy.business_domain=NEW.business_domain
        AND legacy.subject_id=NEW.subject_id
        AND legacy.sample_point_id<>NEW.sample_point_id
        AND NOT (TG_OP='UPDATE'
          AND legacy.business_domain=OLD.business_domain
          AND legacy.subject_id=OLD.subject_id)
      UNION ALL
      SELECT 1
      FROM registry.current_sample_subject_resolution current_resolution
      WHERE current_resolution.source_domain=NEW.business_domain
        AND current_resolution.stable_subject_id=NEW.subject_id
        AND current_resolution.resolution_action='LINK'
        AND current_resolution.target_sample_point_id<>NEW.sample_point_id
    ) THEN
      RAISE EXCEPTION 'stable subject id already points to another sample point';
    END IF;
    IF EXISTS (
      SELECT 1
      FROM registry.sample_point_subject_identity legacy
      WHERE legacy.business_domain=NEW.business_domain
        AND legacy.sample_point_id=NEW.sample_point_id
        AND legacy.subject_id<>NEW.subject_id
        AND NOT (TG_OP='UPDATE'
          AND legacy.business_domain=OLD.business_domain
          AND legacy.subject_id=OLD.subject_id)
      UNION ALL
      SELECT 1
      FROM registry.current_sample_subject_resolution current_resolution
      WHERE current_resolution.source_domain=NEW.business_domain
        AND current_resolution.target_sample_point_id=NEW.sample_point_id
        AND current_resolution.resolution_action='LINK'
        AND current_resolution.stable_subject_id<>NEW.subject_id
    ) THEN
      RAISE EXCEPTION 'target sample point already belongs to another stable subject id';
    END IF;
  END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
ALTER FUNCTION registry.guard_sample_point_subject_identity_consistency()
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.guard_sample_point_subject_identity_consistency()
FROM PUBLIC,qiqihar_enterprise_runtime,qiqihar_master_data_applicant,
  qiqihar_master_data_reviewer,qiqihar_master_data_applier;

DROP TRIGGER IF EXISTS subject_identity_cross_projection_gate
ON registry.sample_point_subject_identity;
CREATE TRIGGER subject_identity_cross_projection_gate
BEFORE INSERT OR UPDATE OR DELETE ON registry.sample_point_subject_identity
FOR EACH ROW EXECUTE FUNCTION registry.guard_sample_point_subject_identity_consistency();

CREATE OR REPLACE FUNCTION platform.register_approved_sample_subject(
    source_domain varchar,
    source_record_id varchar,
    target_sample_point_id uuid)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog,platform,registry,production,market
AS $$
DECLARE
    source_status varchar(30);
    current_sample_point_id uuid;
    resolved_stable_subject_id varchar(500);
    approved_at timestamptz;
    target_snapshot jsonb;
BEGIN
    IF $1 NOT IN ('PRODUCTION','MARKET') THEN
        RAISE EXCEPTION 'unsupported subject source domain';
    END IF;
    IF $2 IS NULL OR btrim($2)='' THEN
        RAISE EXCEPTION 'subject source record is required';
    END IF;

    IF $1='PRODUCTION' THEN
        SELECT record.status_code,record.sample_point_id,metadata.value
          INTO source_status,current_sample_point_id,resolved_stable_subject_id
        FROM production.production_record record
        JOIN production.production_record_submission_metadata metadata
          ON metadata.record_id=record.record_id
         AND metadata.field_code='PROD_SAMPLE_SUBJECT_CODE'
        WHERE record.record_id=$2;
    ELSE
        SELECT record.status_code,record.sample_point_id,core.value
          INTO source_status,current_sample_point_id,resolved_stable_subject_id
        FROM market.market_record record
        JOIN market.market_record_core_value core
          ON core.record_id=record.record_id
         AND core.field_code='MKT_SAMPLE_SUBJECT_CODE'
        WHERE record.record_id=$2;
    END IF;

    IF source_status IS NULL THEN
        RAISE EXCEPTION 'subject source record or stable subject is missing';
    END IF;
    IF source_status<>'APPROVED' OR current_sample_point_id IS NOT NULL THEN
        RAISE EXCEPTION 'subject source must be approved and unlinked';
    END IF;
    SELECT point.updated_at INTO approved_at
    FROM registry.sample_point point
    WHERE point.sample_point_id=$3
      AND point.approval_state='APPROVED';
    IF approved_at IS NULL THEN
        RAISE EXCEPTION 'approved sample point is missing';
    END IF;
    IF session_user=current_user THEN
        RAISE EXCEPTION 'runtime applicant must be distinct from the policy reviewer';
    END IF;

    PERFORM registry.lock_sample_subject_identity_keys(
      $1,ARRAY[resolved_stable_subject_id]::varchar[],ARRAY[$3]::uuid[]);
    IF EXISTS (
      SELECT 1 FROM registry.sample_point_subject_identity legacy
      WHERE legacy.business_domain=$1
        AND legacy.subject_id=resolved_stable_subject_id
        AND legacy.sample_point_id<>$3
      UNION ALL
      SELECT 1 FROM registry.current_sample_subject_resolution current_resolution
      WHERE current_resolution.source_domain=$1
        AND current_resolution.stable_subject_id=resolved_stable_subject_id
        AND current_resolution.resolution_action='LINK'
        AND current_resolution.target_sample_point_id<>$3
    ) THEN
      RAISE EXCEPTION 'stable subject id already points to another sample point';
    END IF;
    IF EXISTS (
      SELECT 1 FROM registry.sample_point_subject_identity legacy
      WHERE legacy.business_domain=$1
        AND legacy.sample_point_id=$3
        AND legacy.subject_id<>resolved_stable_subject_id
      UNION ALL
      SELECT 1 FROM registry.current_sample_subject_resolution current_resolution
      WHERE current_resolution.source_domain=$1
        AND current_resolution.target_sample_point_id=$3
        AND current_resolution.resolution_action='LINK'
        AND current_resolution.stable_subject_id<>resolved_stable_subject_id
    ) THEN
      RAISE EXCEPTION 'target sample point already belongs to another stable subject id';
    END IF;

    target_snapshot := jsonb_build_object(
      'business_domain',$1,
      'subject_id',resolved_stable_subject_id,
      'sample_point_id',$3,
      'created_at',approved_at,
      'created_by','database-master-data-automation');
    RETURN platform.govern_master_data_change(
      'SUBJECT',$1 || ':' || resolved_stable_subject_id,'INSERT',target_snapshot,
      approved_at,session_user::varchar,current_user::varchar,
      $1 || ' approved record accepted by the database policy owner');
END;
$$;
ALTER FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid)
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid)
FROM PUBLIC,qiqihar_master_data_applicant,qiqihar_master_data_reviewer,
  qiqihar_master_data_applier;
GRANT EXECUTE ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid)
TO qiqihar_enterprise_runtime;
COMMENT ON FUNCTION platform.register_approved_sample_subject(varchar,varchar,uuid) IS
  'Runtime-only stable-subject entry; actors stay bound to session_user and the fixed policy owner, with cross-projection identity locking.';

CREATE TABLE overview.region_surplus_calculation_contract (
    version_code varchar(40) PRIMARY KEY,
    name varchar(120) NOT NULL,
    status_code varchar(20) NOT NULL DEFAULT 'RETIRED'
        CHECK (status_code IN ('PENDING','ACTIVE','RETIRED')),
    effective_from timestamptz,
    effective_to timestamptz,
    production_identity_source varchar(80) NOT NULL,
    production_cutoff_source varchar(80) NOT NULL,
    formula varchar(500) NOT NULL,
    activated_by varchar(120),
    activation_basis varchar(500),
    activated_at timestamptz,
    CHECK (btrim(version_code) <> ''),
    CHECK (
      (status_code='PENDING' AND effective_from IS NULL AND effective_to IS NULL
        AND activated_by IS NULL AND activation_basis IS NULL AND activated_at IS NULL)
      OR
      (status_code='ACTIVE' AND effective_from IS NOT NULL AND effective_to IS NULL
        AND btrim(activated_by)<>'' AND btrim(activation_basis)<>'' AND activated_at IS NOT NULL)
      OR
      (status_code='RETIRED' AND effective_from IS NOT NULL AND effective_to>effective_from
        AND btrim(activated_by)<>'' AND btrim(activation_basis)<>'' AND activated_at IS NOT NULL))
);

INSERT INTO overview.region_surplus_calculation_contract(
  version_code,name,status_code,effective_from,effective_to,production_identity_source,
  production_cutoff_source,formula,activated_by,activation_basis,activated_at)
VALUES
 ('REGION_SURPLUS_V1','地区余粮口径第1版','ACTIVE',TIMESTAMPTZ '1900-01-01 00:00:00+08',NULL,
  'PROD_SURPLUS_SUBJECT_CODE','PROD_SURPLUS_CUTOFF_DATE',
  '按人工治理主体键互斥采用产情期末余粮与市场现有库存',
  'V118_MIGRATION','保留迁移前已审核记录及不可变报告快照','1900-01-01 00:00:00+08'),
 ('REGION_SURPLUS_V2','地区余粮口径第2版','PENDING',NULL,NULL,
  'production.production_record.sample_point_id','survey_year/survey_month',
  '按系统治理样本点、数据时间、地区和最新审核版本采用产情期末余粮并与市场库存互斥',
  NULL,NULL,NULL);

CREATE UNIQUE INDEX region_surplus_calculation_contract_open_version
    ON overview.region_surplus_calculation_contract((status_code))
    WHERE status_code='ACTIVE';

ALTER TABLE overview.region_surplus_calculation_contract
  ADD CONSTRAINT region_surplus_calculation_contract_no_overlap
  EXCLUDE USING gist (tstzrange(effective_from,effective_to,'[)') WITH &&)
  WHERE (status_code<>'PENDING');

CREATE OR REPLACE FUNCTION overview.require_contiguous_region_surplus_contracts()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active_count integer; gap_count integer;
BEGIN
  SELECT count(*) INTO active_count FROM overview.region_surplus_calculation_contract
  WHERE status_code='ACTIVE';
  IF active_count<>1 THEN
    RAISE EXCEPTION 'region surplus calculation contract must have exactly one active version';
  END IF;
  SELECT count(*) INTO active_count FROM overview.region_surplus_calculation_contract
  WHERE status_code='ACTIVE' AND effective_from<=CURRENT_TIMESTAMP
    AND (effective_to IS NULL OR CURRENT_TIMESTAMP<effective_to);
  IF active_count<>1 THEN
    RAISE EXCEPTION 'current time must match exactly one active region surplus calculation contract';
  END IF;
  SELECT count(*) INTO gap_count FROM (
    SELECT effective_from,lag(effective_to) OVER(ORDER BY effective_from) previous_to,
      row_number() OVER(ORDER BY effective_from) row_no
    FROM overview.region_surplus_calculation_contract WHERE status_code<>'PENDING'
  ) ordered WHERE row_no>1 AND previous_to IS DISTINCT FROM effective_from;
  IF gap_count<>0 THEN
    RAISE EXCEPTION 'region surplus calculation contract ranges must be contiguous';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER region_surplus_calculation_contract_contiguous
AFTER INSERT OR UPDATE OR DELETE ON overview.region_surplus_calculation_contract
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION overview.require_contiguous_region_surplus_contracts();

CREATE TABLE overview.region_surplus_calculation_activation_audit (
  activation_id bigserial PRIMARY KEY,
  version_code varchar(40) NOT NULL,
  previous_version_code varchar(40) NOT NULL,
  effective_from timestamptz NOT NULL,
  activated_by varchar(120) NOT NULL CHECK (btrim(activated_by)<>''),
  activation_basis varchar(500) NOT NULL CHECK (btrim(activation_basis)<>''),
  activated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(version_code)
);

CREATE OR REPLACE FUNCTION overview.activate_region_surplus_calculation_contract(
  target_version varchar, activation_boundary timestamptz, actor varchar, basis varchar)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE current_contract overview.region_surplus_calculation_contract%ROWTYPE;
DECLARE candidate overview.region_surplus_calculation_contract%ROWTYPE;
BEGIN
  IF activation_boundary IS NULL OR btrim(coalesce(actor,''))='' OR btrim(coalesce(basis,''))='' THEN
    RAISE EXCEPTION 'activation boundary, actor and basis are required';
  END IF;
  SELECT * INTO current_contract FROM overview.region_surplus_calculation_contract
  WHERE status_code='ACTIVE' FOR UPDATE;
  SELECT * INTO candidate FROM overview.region_surplus_calculation_contract
  WHERE version_code=target_version AND status_code='PENDING' FOR UPDATE;
  IF current_contract.version_code IS NULL OR candidate.version_code IS NULL THEN
    RAISE EXCEPTION 'active or pending region surplus calculation contract is missing';
  END IF;
  IF activation_boundary<=current_contract.effective_from OR activation_boundary>clock_timestamp() THEN
    RAISE EXCEPTION 'activation boundary is outside the auditable range';
  END IF;
  UPDATE overview.region_surplus_calculation_contract
  SET status_code='RETIRED',effective_to=activation_boundary
  WHERE version_code=current_contract.version_code;
  UPDATE overview.region_surplus_calculation_contract
  SET status_code='ACTIVE',effective_from=activation_boundary,
      activated_by=actor,activation_basis=basis,activated_at=clock_timestamp()
  WHERE version_code=candidate.version_code;
  INSERT INTO overview.region_surplus_calculation_activation_audit(
    version_code,previous_version_code,effective_from,activated_by,activation_basis)
  VALUES(candidate.version_code,current_contract.version_code,activation_boundary,actor,basis);
END $$;

COMMENT ON TABLE overview.region_surplus_calculation_contract IS
    'Audited, non-overlapping and contiguous region-surplus calculation contracts. Pending versions have no guessed effective date.';

CREATE TABLE market.sample_point_inventory_contract (
  sample_point_id uuid PRIMARY KEY REFERENCES registry.sample_point(sample_point_id),
  object_type_code varchar(60) NOT NULL REFERENCES platform.object_type(code),
  ownership_type varchar(30) NOT NULL CHECK (ownership_type IN ('OWNED','CUSTODIAL')),
  cargo_owner_party_id uuid NOT NULL REFERENCES market.business_party(party_id),
  policy_attribute varchar(40) NOT NULL
    CHECK (policy_attribute IN ('COMMERCIAL','POLICY','POLICY_AND_COMMERCIAL')),
  effective_from date NOT NULL,
  approved_by varchar(120) NOT NULL CHECK (btrim(approved_by)<>''),
  approval_basis varchar(500) NOT NULL CHECK (btrim(approval_basis)<>''),
  approved_at timestamptz NOT NULL
);

CREATE TABLE market.market_inventory_governance (
  record_id varchar(100) PRIMARY KEY REFERENCES market.market_record(record_id) ON DELETE CASCADE,
  status_code varchar(30) NOT NULL CHECK (status_code IN ('PENDING_REVIEW','READY')),
  reason_code varchar(60) NOT NULL,
  sample_point_id uuid REFERENCES registry.sample_point(sample_point_id),
  resolved_by varchar(120),
  resolved_at timestamptz,
  CHECK ((status_code='PENDING_REVIEW' AND resolved_by IS NULL AND resolved_at IS NULL)
    OR (status_code='READY' AND sample_point_id IS NOT NULL
      AND btrim(resolved_by)<>'' AND resolved_at IS NOT NULL))
);

ALTER TABLE registry.sample_subject_resolution_item
ADD COLUMN expected_predecessor_resolution_revision_id uuid
  REFERENCES registry.sample_subject_resolution_revision(resolution_revision_id);

COMMENT ON COLUMN registry.sample_subject_resolution_item.expected_predecessor_resolution_revision_id IS
  'Required optimistic predecessor for an append-only correction of an approved market inventory identity.';

-- Keep the V106 append-only resolution/audit mechanism, but allow an inventory
-- record that is deliberately waiting for review to receive an explicit LINK.
-- No other pending record, domain or action is admitted by this exception.
CREATE OR REPLACE FUNCTION registry.apply_sample_subject_resolution(
  p_batch_id uuid,p_actor varchar)
RETURNS varchar LANGUAGE plpgsql AS $$
DECLARE
    batch_row registry.sample_subject_resolution_batch%ROWTYPE;
    item_row registry.sample_subject_resolution_item%ROWTYPE;
    item_count integer;
    current_version bigint;
    current_sample_point uuid;
    current_status varchar(30);
    current_return_reason varchar(500);
    current_subject varchar(500);
    before_value jsonb;
    after_value jsonb;
    before_hash char(64);
    after_hash char(64);
    revision_id uuid;
    next_sequence bigint;
    identity_source_domain varchar(30);
BEGIN
    IF p_actor IS NULL OR btrim(p_actor)='' THEN RAISE EXCEPTION 'resolution actor is required'; END IF;
    SELECT * INTO batch_row FROM registry.sample_subject_resolution_batch
    WHERE batch_id=p_batch_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'resolution batch not found'; END IF;
    IF batch_row.status_code='APPLIED' THEN
        INSERT INTO registry.sample_subject_resolution_audit
        VALUES(gen_random_uuid(),p_batch_id,'APPLY_NOOP',p_actor,now(),
          jsonb_build_object('idempotencyKey',batch_row.idempotency_key));
        RETURN 'ALREADY_APPLIED';
    END IF;
    IF batch_row.status_code<>'STAGED' THEN RAISE EXCEPTION 'resolution batch is not staged'; END IF;
    SELECT count(*) INTO item_count FROM registry.sample_subject_resolution_item WHERE batch_id=p_batch_id;
    IF item_count<>batch_row.expected_item_count THEN
        RAISE EXCEPTION 'resolution item count mismatch: expected %, found %',batch_row.expected_item_count,item_count;
    END IF;

    -- Acquire every domain's complete batch set through the closed shared
    -- helper before any item identity check or revision write.
    FOR identity_source_domain IN
      SELECT DISTINCT lock_item.source_domain
      FROM registry.sample_subject_resolution_item lock_item
      WHERE lock_item.batch_id=p_batch_id
        AND lock_item.resolution_action='LINK'
      ORDER BY lock_item.source_domain
    LOOP
      PERFORM registry.lock_sample_subject_identity_keys(
        identity_source_domain,
        ARRAY(SELECT DISTINCT lock_item.stable_subject_id
          FROM registry.sample_subject_resolution_item lock_item
          WHERE lock_item.batch_id=p_batch_id
            AND lock_item.source_domain=identity_source_domain
            AND lock_item.resolution_action='LINK'),
        ARRAY(SELECT DISTINCT lock_item.target_sample_point_id
          FROM registry.sample_subject_resolution_item lock_item
          WHERE lock_item.batch_id=p_batch_id
            AND lock_item.source_domain=identity_source_domain
            AND lock_item.resolution_action='LINK'));
    END LOOP;

    FOR item_row IN SELECT * FROM registry.sample_subject_resolution_item
        WHERE batch_id=p_batch_id ORDER BY item_sequence FOR UPDATE
    LOOP
        current_subject:=NULL;
        IF item_row.source_domain='PRODUCTION' THEN
            SELECT version,sample_point_id,status_code,return_reason
              INTO current_version,current_sample_point,current_status,current_return_reason
              FROM production.production_record WHERE record_id=item_row.source_record_id FOR SHARE;
            SELECT value INTO current_subject FROM production.production_record_submission_metadata
              WHERE record_id=item_row.source_record_id AND field_code='PROD_SAMPLE_SUBJECT_CODE';
        ELSE
            SELECT version,sample_point_id,status_code,return_reason
              INTO current_version,current_sample_point,current_status,current_return_reason
              FROM market.market_record WHERE record_id=item_row.source_record_id FOR SHARE;
            SELECT value INTO current_subject FROM market.market_record_core_value
              WHERE record_id=item_row.source_record_id AND field_code='MKT_SAMPLE_SUBJECT_CODE';
        END IF;
        IF current_version IS NULL THEN
            RAISE EXCEPTION 'resolution source record not found: %/%',item_row.source_domain,item_row.source_record_id;
        END IF;
        IF current_version<>item_row.expected_source_version THEN
            RAISE EXCEPTION 'resolution source version mismatch for %/%: expected %, found %',
                item_row.source_domain,item_row.source_record_id,item_row.expected_source_version,current_version;
        END IF;
        IF current_status<>'APPROVED' AND NOT (
            item_row.source_domain='MARKET' AND current_status='PENDING_REVIEW'
            AND item_row.resolution_action='LINK'
            AND EXISTS(SELECT 1 FROM market.market_record_fact fact
              WHERE fact.record_id=item_row.source_record_id AND fact.fact_code='ENDING_INVENTORY')) THEN
            RAISE EXCEPTION 'resolution source must be approved or pending inventory review: %/%',
              item_row.source_domain,item_row.source_record_id;
        END IF;
        IF EXISTS(SELECT 1 FROM registry.current_sample_subject_resolution current_resolution
          WHERE current_resolution.source_domain=item_row.source_domain
            AND current_resolution.source_record_id=item_row.source_record_id) THEN
            RAISE EXCEPTION 'resolution source already has an active appended resolution';
        END IF;

        IF item_row.resolution_action='LINK' THEN
            IF NOT EXISTS(SELECT 1 FROM registry.sample_point point
                WHERE point.sample_point_id=item_row.target_sample_point_id
                  AND point.approval_state='APPROVED') THEN
                RAISE EXCEPTION 'resolution target sample point is not approved';
            END IF;
            IF item_row.source_domain='MARKET' AND current_status='PENDING_REVIEW'
              AND NOT EXISTS(
                SELECT 1 FROM market.market_record record
                JOIN registry.sample_point point
                  ON point.sample_point_id=item_row.target_sample_point_id
                WHERE record.record_id=item_row.source_record_id
                  AND point.region_code=record.region_code
                  AND point.approval_state='APPROVED'
                  AND point.location_state='VALID'
                  AND point.effective_from<=record.trade_date) THEN
                RAISE EXCEPTION 'pending market inventory resolution target is outside its governed region or effective date';
            END IF;
            IF current_subject IS NOT NULL AND current_subject<>item_row.stable_subject_id THEN
                RAISE EXCEPTION 'resolution refuses to replace a different stable subject id';
            END IF;
            IF EXISTS(
              SELECT 1 FROM registry.sample_point_subject_identity legacy
              WHERE legacy.business_domain=item_row.source_domain
                AND legacy.subject_id=item_row.stable_subject_id
                AND legacy.sample_point_id<>item_row.target_sample_point_id
              UNION ALL
              SELECT 1 FROM registry.current_sample_subject_resolution active
              WHERE active.source_domain=item_row.source_domain
                AND active.stable_subject_id=item_row.stable_subject_id
                AND active.resolution_action='LINK'
                AND active.target_sample_point_id<>item_row.target_sample_point_id) THEN
                RAISE EXCEPTION 'stable subject id already points to another sample point';
            END IF;
            IF EXISTS(
              SELECT 1 FROM registry.sample_point_subject_identity legacy
              WHERE legacy.business_domain=item_row.source_domain
                AND legacy.sample_point_id=item_row.target_sample_point_id
                AND legacy.subject_id<>item_row.stable_subject_id
              UNION ALL
              SELECT 1 FROM registry.current_sample_subject_resolution active
              WHERE active.source_domain=item_row.source_domain
                AND active.target_sample_point_id=item_row.target_sample_point_id
                AND active.resolution_action='LINK'
                AND active.stable_subject_id<>item_row.stable_subject_id) THEN
                RAISE EXCEPTION 'target sample point already belongs to another stable subject id';
            END IF;
        END IF;

        before_value:=jsonb_build_object(
          'sourceDomain',item_row.source_domain,'sourceRecordId',item_row.source_record_id,
          'version',current_version,'samplePointId',current_sample_point,
          'statusCode',current_status,'returnReason',current_return_reason,
          'stableSubjectId',current_subject);
        after_value:=jsonb_build_object(
          'sourceDomain',item_row.source_domain,'sourceRecordId',item_row.source_record_id,
          'sourceVersion',current_version,'resolutionAction',item_row.resolution_action,
          'stableSubjectId',item_row.stable_subject_id,
          'targetSamplePointId',item_row.target_sample_point_id);
        before_hash:=encode(sha256(convert_to(before_value::text,'UTF8')),'hex');
        after_hash:=encode(sha256(convert_to(after_value::text,'UTF8')),'hex');
        SELECT COALESCE(max(resolution_sequence),0)+1 INTO next_sequence
        FROM registry.sample_subject_resolution_revision
        WHERE source_domain=item_row.source_domain AND source_record_id=item_row.source_record_id;
        revision_id:=gen_random_uuid();
        INSERT INTO registry.sample_subject_resolution_revision(
          resolution_revision_id,source_domain,source_record_id,resolution_sequence,resolution_action,
          stable_subject_id,target_sample_point_id,source_version,predecessor_revision_id,
          batch_id,item_sequence,before_sha256,after_sha256,occurred_at,actor)
        VALUES(revision_id,item_row.source_domain,item_row.source_record_id,next_sequence,
          item_row.resolution_action,item_row.stable_subject_id,item_row.target_sample_point_id,
          current_version,NULL,p_batch_id,item_row.item_sequence,before_hash,after_hash,now(),p_actor);
        UPDATE registry.sample_subject_resolution_item SET
          before_snapshot=before_value,after_snapshot=after_value,before_sha256=before_hash,
          after_sha256=after_hash,applied_source_version=current_version,
          applied_resolution_revision_id=revision_id,status_code='APPLIED',
          applied_at=now(),applied_by=p_actor
        WHERE batch_id=p_batch_id AND item_sequence=item_row.item_sequence;
        INSERT INTO registry.sample_subject_resolution_audit
        VALUES(gen_random_uuid(),p_batch_id,'ITEM_APPLIED',p_actor,now(),jsonb_build_object(
          'itemSequence',item_row.item_sequence,'sourceDomain',item_row.source_domain,
          'sourceRecordId',item_row.source_record_id,'resolutionRevisionId',revision_id,
          'beforeSnapshot',before_value,'beforeSha256',before_hash,
          'afterSnapshot',after_value,'afterSha256',after_hash));
    END LOOP;

    UPDATE registry.sample_subject_resolution_batch SET status_code='APPLIED',
      applied_at=now(),applied_by=p_actor WHERE batch_id=p_batch_id;
    INSERT INTO registry.sample_subject_resolution_audit
    VALUES(gen_random_uuid(),p_batch_id,'APPLIED',p_actor,now(),
      jsonb_build_object('itemCount',item_count,'inputDigest',batch_row.input_digest));
    RETURN 'APPLIED';
END;
$$;

COMMENT ON FUNCTION registry.apply_sample_subject_resolution(uuid,varchar) IS
  'Append-only explicit subject resolution; pending market inventory permits controlled LINK only.';

-- CREATE OR REPLACE clears per-function configuration. Restore the V112
-- ownership, execution boundary, and fixed search path explicitly so the
-- actor-bearing implementation cannot become a runtime entry point.
ALTER FUNCTION registry.apply_sample_subject_resolution(uuid,varchar)
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.apply_sample_subject_resolution(uuid,varchar)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
  qiqihar_master_data_reviewer, qiqihar_master_data_applier;
ALTER FUNCTION registry.apply_sample_subject_resolution(uuid,varchar)
SET search_path = pg_catalog,registry,production,market;

-- Approved inventory records cannot return to an editable workflow state. A
-- mistaken governed identity is therefore corrected by appending a new LINK
-- revision against an explicit current predecessor. The identity projection,
-- six private inventory values, governance state, and source version change in
-- this one transaction; source facts and prior revisions remain immutable.
CREATE FUNCTION registry.correct_approved_market_inventory_resolution(
  p_batch_id uuid,p_actor varchar)
RETURNS varchar LANGUAGE plpgsql AS $$
DECLARE
    batch_row registry.sample_subject_resolution_batch%ROWTYPE;
    item_row registry.sample_subject_resolution_item%ROWTYPE;
    current_resolution registry.current_sample_subject_resolution%ROWTYPE;
    item_count integer;
    current_version bigint;
    current_party_id uuid;
    current_sample_point_id uuid;
    record_product_code varchar(60);
    record_object_type_code varchar(60);
    record_region_code varchar(30);
    record_trade_date date;
    governance_status varchar(30);
    governance_sample_point_id uuid;
    target_party_id uuid;
    target_region_code varchar(30);
    target_ownership_type varchar(30);
    target_cargo_owner_party_id uuid;
    target_policy_attribute varchar(40);
    target_cutoff_date date;
    current_inventory_context jsonb;
    target_inventory_context jsonb;
    before_value jsonb;
    after_value jsonb;
    before_hash char(64);
    after_hash char(64);
    revision_id uuid;
    business_event_id uuid;
    business_actor varchar(120);
    business_work_unit varchar(60);
    business_event_detail jsonb;
    correction_occurred_at timestamptz;
    updated_count integer;
BEGIN
    IF p_actor IS NULL OR btrim(p_actor)='' THEN
        RAISE EXCEPTION 'resolution actor is required';
    END IF;
    SELECT * INTO batch_row FROM registry.sample_subject_resolution_batch
    WHERE batch_id=p_batch_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'resolution batch not found'; END IF;
    IF batch_row.status_code='APPLIED' THEN
        INSERT INTO registry.sample_subject_resolution_audit
        VALUES(gen_random_uuid(),p_batch_id,'APPLY_NOOP',p_actor,now(),
          jsonb_build_object('idempotencyKey',batch_row.idempotency_key));
        RETURN 'ALREADY_APPLIED';
    END IF;
    IF batch_row.status_code<>'STAGED' THEN
        RAISE EXCEPTION 'resolution batch is not staged';
    END IF;
    SELECT security_user.subject_id,security_user.work_unit_code
      INTO business_actor,business_work_unit
    FROM platform.security_user security_user
    WHERE security_user.subject_id=batch_row.created_by AND security_user.enabled;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'approved inventory correction business actor is not an enabled platform user';
    END IF;
    SELECT count(*) INTO item_count FROM registry.sample_subject_resolution_item
    WHERE batch_id=p_batch_id;
    IF item_count<>batch_row.expected_item_count THEN
        RAISE EXCEPTION 'resolution item count mismatch: expected %, found %',
          batch_row.expected_item_count,item_count;
    END IF;

    -- The correction entry delegates the complete batch set to the same
    -- closed helper used by initial LINK and legacy registration.
    PERFORM registry.lock_sample_subject_identity_keys(
      'MARKET',
      ARRAY(SELECT DISTINCT lock_item.stable_subject_id
        FROM registry.sample_subject_resolution_item lock_item
        WHERE lock_item.batch_id=p_batch_id
          AND lock_item.source_domain='MARKET'
          AND lock_item.resolution_action='LINK'),
      ARRAY(SELECT DISTINCT lock_item.target_sample_point_id
        FROM registry.sample_subject_resolution_item lock_item
        WHERE lock_item.batch_id=p_batch_id
          AND lock_item.source_domain='MARKET'
          AND lock_item.resolution_action='LINK'));

    FOR item_row IN SELECT * FROM registry.sample_subject_resolution_item
        WHERE batch_id=p_batch_id ORDER BY item_sequence FOR UPDATE
    LOOP
        IF item_row.source_domain<>'MARKET' OR item_row.resolution_action<>'LINK' THEN
            RAISE EXCEPTION 'approved inventory correction requires a MARKET LINK item';
        END IF;
        IF item_row.expected_predecessor_resolution_revision_id IS NULL THEN
            RAISE EXCEPTION 'approved inventory correction predecessor is required';
        END IF;

        SELECT record.version,record.party_id,record.sample_point_id,record.product_code,
               record.object_type_code,record.region_code,record.trade_date
          INTO current_version,current_party_id,current_sample_point_id,record_product_code,
               record_object_type_code,record_region_code,record_trade_date
        FROM market.market_record record
        WHERE record.record_id=item_row.source_record_id
          AND record.status_code='APPROVED'
          AND EXISTS(SELECT 1 FROM market.market_record_fact fact
            WHERE fact.record_id=record.record_id AND fact.fact_code='ENDING_INVENTORY')
        FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'approved market inventory correction source not found';
        END IF;
        IF current_version<>item_row.expected_source_version THEN
            RAISE EXCEPTION 'resolution source version mismatch for MARKET/%: expected %, found %',
              item_row.source_record_id,item_row.expected_source_version,current_version;
        END IF;

        SELECT * INTO current_resolution
        FROM registry.current_sample_subject_resolution resolution
        WHERE resolution.source_domain='MARKET'
          AND resolution.source_record_id=item_row.source_record_id;
        IF NOT FOUND OR current_resolution.resolution_revision_id
              IS DISTINCT FROM item_row.expected_predecessor_resolution_revision_id THEN
            RAISE EXCEPTION 'approved inventory correction predecessor is not the current appended revision';
        END IF;
        IF current_resolution.resolution_action<>'LINK'
          OR current_resolution.target_sample_point_id IS DISTINCT FROM current_sample_point_id THEN
            RAISE EXCEPTION 'approved inventory identity is not aligned with its current resolution';
        END IF;

        SELECT governance.status_code,governance.sample_point_id
          INTO governance_status,governance_sample_point_id
        FROM market.market_inventory_governance governance
        WHERE governance.record_id=item_row.source_record_id FOR UPDATE;
        IF governance_status IS DISTINCT FROM 'READY'
          OR governance_sample_point_id IS DISTINCT FROM current_sample_point_id THEN
            RAISE EXCEPTION 'approved inventory governance is not aligned with its current resolution';
        END IF;

        SELECT point.owner_party_id,point.region_code,profile.ownership_type,
               profile.cargo_owner_party_id,profile.policy_attribute
          INTO target_party_id,target_region_code,target_ownership_type,
               target_cargo_owner_party_id,target_policy_attribute
        FROM registry.sample_point point
        JOIN market.sample_point_inventory_contract profile
          ON profile.sample_point_id=point.sample_point_id
         AND profile.object_type_code=record_object_type_code
         AND profile.effective_from<=record_trade_date
        WHERE point.sample_point_id=item_row.target_sample_point_id
          AND point.owner_party_id IS NOT NULL
          AND point.region_code=record_region_code
          AND point.approval_state='APPROVED'
          AND point.location_state='VALID'
          AND point.effective_from<=record_trade_date;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'approved inventory correction target is outside its governed region, type, or effective date';
        END IF;
        IF NOT ((target_ownership_type='OWNED' AND target_party_id=target_cargo_owner_party_id)
          OR (target_ownership_type='CUSTODIAL' AND target_party_id<>target_cargo_owner_party_id)) THEN
            RAISE EXCEPTION 'approved inventory correction target ownership profile is inconsistent';
        END IF;

        IF EXISTS(
          SELECT 1 FROM registry.sample_point_subject_identity legacy
          WHERE legacy.business_domain='MARKET'
            AND legacy.subject_id=item_row.stable_subject_id
            AND legacy.sample_point_id<>item_row.target_sample_point_id
          UNION ALL
          SELECT 1 FROM registry.current_sample_subject_resolution active
          WHERE active.source_domain='MARKET'
            AND active.stable_subject_id=item_row.stable_subject_id
            AND active.resolution_action='LINK'
            AND active.target_sample_point_id<>item_row.target_sample_point_id) THEN
            RAISE EXCEPTION 'stable subject id already points to another sample point';
        END IF;
        IF EXISTS(
          SELECT 1 FROM registry.sample_point_subject_identity legacy
          WHERE legacy.business_domain='MARKET'
            AND legacy.sample_point_id=item_row.target_sample_point_id
            AND legacy.subject_id<>item_row.stable_subject_id
          UNION ALL
          SELECT 1 FROM registry.current_sample_subject_resolution active
          WHERE active.source_domain='MARKET'
            AND active.target_sample_point_id=item_row.target_sample_point_id
            AND active.resolution_action='LINK'
            AND active.stable_subject_id<>item_row.stable_subject_id) THEN
            RAISE EXCEPTION 'target sample point already belongs to another stable subject id';
        END IF;

        SELECT coalesce(jsonb_object_agg(value.field_code,value.value),'{}'::jsonb)
          INTO current_inventory_context
        FROM market.market_record_core_value value
        WHERE value.record_id=item_row.source_record_id
          AND value.field_code IN ('MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE',
            'MKT_STORAGE_REGION_CODE','MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE',
            'MKT_INVENTORY_POLICY_ATTRIBUTE');
        target_cutoff_date:=(date_trunc('month',record_trade_date)::date
          + interval '1 month - 1 day')::date;
        target_inventory_context:=jsonb_build_object(
          'MKT_INVENTORY_HOLDER_CODE',target_party_id::text,
          'MKT_INVENTORY_OWNERSHIP_TYPE',target_ownership_type,
          'MKT_STORAGE_REGION_CODE',target_region_code,
          'MKT_CARGO_OWNER_CODE',target_cargo_owner_party_id::text,
          'MKT_INVENTORY_CUTOFF_DATE',target_cutoff_date::text,
          'MKT_INVENTORY_POLICY_ATTRIBUTE',target_policy_attribute);
        before_value:=jsonb_build_object(
          'sourceDomain','MARKET','sourceRecordId',item_row.source_record_id,
          'version',current_version,'partyId',current_party_id,
          'samplePointId',current_sample_point_id,
          'resolutionRevisionId',current_resolution.resolution_revision_id,
          'stableSubjectId',current_resolution.stable_subject_id,
          'inventoryContext',current_inventory_context);
        after_value:=jsonb_build_object(
          'sourceDomain','MARKET','sourceRecordId',item_row.source_record_id,
          'sourceVersion',current_version+1,'resolutionAction','LINK',
          'partyId',target_party_id,'targetSamplePointId',item_row.target_sample_point_id,
          'stableSubjectId',item_row.stable_subject_id,
          'inventoryContext',target_inventory_context);
        before_hash:=encode(sha256(convert_to(before_value::text,'UTF8')),'hex');
        after_hash:=encode(sha256(convert_to(after_value::text,'UTF8')),'hex');

        correction_occurred_at:=clock_timestamp();
        UPDATE market.market_record SET party_id=target_party_id,
          sample_point_id=item_row.target_sample_point_id,version=current_version+1,
          last_modified_by=business_actor,updated_at=correction_occurred_at
        WHERE record_id=item_row.source_record_id AND version=current_version
          AND party_id=current_party_id AND sample_point_id=current_sample_point_id
          AND status_code='APPROVED';
        GET DIAGNOSTICS updated_count=ROW_COUNT;
        IF updated_count<>1 THEN
            RAISE EXCEPTION 'approved inventory correction source changed concurrently';
        END IF;

        INSERT INTO market.market_record_core_value(
          record_id,product_code,field_code,domain_binding,value)
        SELECT item_row.source_record_id,record_product_code,context.field_code,'EXTENSION',context.value
        FROM jsonb_each_text(target_inventory_context) context(field_code,value)
        ON CONFLICT(record_id,field_code) DO UPDATE SET value=excluded.value;
        INSERT INTO market.market_inventory_governance(
          record_id,status_code,reason_code,sample_point_id,resolved_by,resolved_at)
        VALUES(item_row.source_record_id,'READY','APPROVED_SAMPLE_POINT_PROFILE_CORRECTED',
          item_row.target_sample_point_id,p_actor,now())
        ON CONFLICT(record_id) DO UPDATE SET status_code='READY',
          reason_code='APPROVED_SAMPLE_POINT_PROFILE_CORRECTED',
          sample_point_id=excluded.sample_point_id,resolved_by=excluded.resolved_by,
          resolved_at=excluded.resolved_at;

        revision_id:=gen_random_uuid();
        INSERT INTO registry.sample_subject_resolution_revision(
          resolution_revision_id,source_domain,source_record_id,resolution_sequence,resolution_action,
          stable_subject_id,target_sample_point_id,source_version,predecessor_revision_id,
          batch_id,item_sequence,before_sha256,after_sha256,occurred_at,actor)
        VALUES(revision_id,'MARKET',item_row.source_record_id,
          current_resolution.resolution_sequence+1,'LINK',item_row.stable_subject_id,
          item_row.target_sample_point_id,current_version+1,
          current_resolution.resolution_revision_id,p_batch_id,item_row.item_sequence,
          before_hash,after_hash,now(),p_actor);
        UPDATE registry.sample_subject_resolution_item SET
          before_snapshot=before_value,after_snapshot=after_value,before_sha256=before_hash,
          after_sha256=after_hash,applied_source_version=current_version,
          applied_resolution_revision_id=revision_id,status_code='APPLIED',
          applied_at=now(),applied_by=p_actor
        WHERE batch_id=p_batch_id AND item_sequence=item_row.item_sequence;
        INSERT INTO registry.sample_subject_resolution_audit
        VALUES(gen_random_uuid(),p_batch_id,'ITEM_APPLIED',p_actor,now(),jsonb_build_object(
          'itemSequence',item_row.item_sequence,'sourceDomain','MARKET',
          'sourceRecordId',item_row.source_record_id,'resolutionRevisionId',revision_id,
          'predecessorRevisionId',current_resolution.resolution_revision_id,
          'executionActor',p_actor,
          'beforeSnapshot',before_value,'beforeSha256',before_hash,
          'afterSnapshot',after_value,'afterSha256',after_hash));

        business_event_id:=gen_random_uuid();
        business_event_detail:=jsonb_build_object(
          'regionCodes',jsonb_build_array(record_region_code),
          'regionCode',record_region_code,'productCode',record_product_code,
          'sourceVersion',current_version,'correctedVersion',current_version+1,
          'resolutionRevisionId',revision_id,
          'predecessorResolutionRevisionId',current_resolution.resolution_revision_id);
        INSERT INTO platform.business_audit_event(
          event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
          work_unit_code,occurred_at,detail)
        VALUES(business_event_id,'MARKET_RECORD',item_row.source_record_id,
          'MARKET_INVENTORY_IDENTITY_CORRECTED',business_actor,business_work_unit,
          correction_occurred_at,business_event_detail);
        INSERT INTO platform.business_event_outbox(
          event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
          work_unit_code,region_codes,product_code,occurred_at,detail)
        VALUES(business_event_id,'MARKET_RECORD',item_row.source_record_id,
          'MARKET_INVENTORY_IDENTITY_CORRECTED',business_actor,business_work_unit,
          ARRAY[record_region_code]::varchar(18)[],record_product_code,
          correction_occurred_at,business_event_detail);
    END LOOP;

    UPDATE registry.sample_subject_resolution_batch SET status_code='APPLIED',
      applied_at=now(),applied_by=p_actor WHERE batch_id=p_batch_id;
    INSERT INTO registry.sample_subject_resolution_audit
    VALUES(gen_random_uuid(),p_batch_id,'APPLIED',p_actor,now(),
      jsonb_build_object('itemCount',item_count,'inputDigest',batch_row.input_digest,
        'operation','APPROVED_MARKET_INVENTORY_FORWARD_CORRECTION',
        'executionActor',p_actor,'businessActor',business_actor));
    RETURN 'APPLIED';
END;
$$;

COMMENT ON FUNCTION registry.correct_approved_market_inventory_resolution(uuid,varchar) IS
  'Append-only, explicit-predecessor correction of an approved market inventory identity and its governed projection.';
ALTER FUNCTION registry.correct_approved_market_inventory_resolution(uuid,varchar)
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.correct_approved_market_inventory_resolution(uuid,varchar)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
  qiqihar_master_data_reviewer, qiqihar_master_data_applier;
ALTER FUNCTION registry.correct_approved_market_inventory_resolution(uuid,varchar)
SET search_path = pg_catalog,registry,production,market;

CREATE FUNCTION registry.correct_approved_market_inventory_resolution(p_batch_id uuid)
RETURNS varchar
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog,registry,production,market
AS $$
    SELECT registry.correct_approved_market_inventory_resolution(
      p_batch_id,session_user::varchar)
$$;
ALTER FUNCTION registry.correct_approved_market_inventory_resolution(uuid)
OWNER TO qiqihar_migration_owner;
REVOKE ALL ON FUNCTION registry.correct_approved_market_inventory_resolution(uuid)
FROM PUBLIC, qiqihar_enterprise_runtime, qiqihar_master_data_applicant,
  qiqihar_master_data_reviewer;
GRANT EXECUTE ON FUNCTION registry.correct_approved_market_inventory_resolution(uuid)
TO qiqihar_master_data_applier;
GRANT SELECT ON market.sample_point_inventory_contract
TO qiqihar_migration_owner;
GRANT SELECT,INSERT,UPDATE ON market.market_inventory_governance
TO qiqihar_migration_owner;

INSERT INTO market.market_inventory_governance(record_id,status_code,reason_code)
SELECT DISTINCT fact.record_id,'PENDING_REVIEW','PRE_V118_REVIEW_REQUIRED'
FROM market.market_record_fact fact WHERE fact.fact_code='ENDING_INVENTORY';

-- Keep the database provenance gate aligned with the public production data
-- time contract. survey_date is a compatibility projection and must not
-- decide quarterly adoption when survey_month is explicitly governed.
CREATE OR REPLACE FUNCTION supply.validate_release_period_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE release_row supply.source_release%ROWTYPE;
BEGIN
    SELECT * INTO release_row FROM supply.source_release WHERE source_release_id=NEW.source_release_id;
    IF release_row.source_domain='PRODUCTION' AND NOT EXISTS(
        SELECT 1 FROM production.production_record record
        JOIN platform.supply_survey_period period ON period.code=release_row.period_code
        WHERE record.record_id=release_row.source_record_id AND record.version=release_row.source_version
          AND record.product_code=release_row.product_code AND record.region_code=release_row.region_code
          AND record.survey_period_governance_state='CONFIRMED'
          AND record.survey_year=period.survey_year
          AND (period.survey_quarter IS NULL OR (record.survey_month IS NOT NULL
            AND period.survey_quarter='Q'||EXTRACT(QUARTER FROM
              make_date(record.survey_year,record.survey_month,1))::integer::text))) THEN
        RAISE EXCEPTION 'production source does not belong to the supply survey period';
    ELSIF release_row.source_domain='LOGISTICS' AND NOT EXISTS(
        SELECT 1 FROM logistics.route_event event
        JOIN platform.supply_survey_period period ON period.code=release_row.period_code
        WHERE event.event_id::text=release_row.source_record_id AND event.version=release_row.source_version
          AND event.product_code=release_row.product_code
          AND COALESCE(event.business_region_code,
            CASE event.direction_code WHEN 'INFLOW' THEN event.destination_region_code
              ELSE event.origin_region_code END)=release_row.region_code
          AND event.survey_period_governance_state='CONFIRMED'
          AND event.survey_year=period.survey_year
          AND (period.survey_quarter IS NULL OR (event.survey_month IS NOT NULL
            AND period.survey_quarter='Q'||EXTRACT(QUARTER FROM
              make_date(event.survey_year,event.survey_month,1))::integer::text))) THEN
        RAISE EXCEPTION 'logistics source does not belong to the supply survey period';
    ELSIF release_row.source_domain='MANUAL' AND NOT EXISTS(
        SELECT 1 FROM supply.manual_input_decision decision
        WHERE decision.manual_input_id=NEW.manual_input_id
          AND decision.period_code=release_row.period_code) THEN
        RAISE EXCEPTION 'manual source does not belong to the supply business period';
    END IF;
    RETURN NEW;
END $$;
