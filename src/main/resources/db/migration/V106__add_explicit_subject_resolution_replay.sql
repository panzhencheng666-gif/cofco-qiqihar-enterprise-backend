CREATE TABLE registry.sample_subject_resolution_batch (
    batch_id uuid PRIMARY KEY,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    input_digest char(64) NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    expected_item_count integer NOT NULL CHECK (expected_item_count > 0),
    status_code varchar(20) NOT NULL CHECK (status_code IN ('STAGED','APPLIED','ROLLED_BACK')),
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    applied_at timestamptz,
    applied_by varchar(120),
    rolled_back_at timestamptz,
    rolled_back_by varchar(120),
    CHECK ((status_code='STAGED' AND applied_at IS NULL AND applied_by IS NULL
              AND rolled_back_at IS NULL AND rolled_back_by IS NULL)
        OR (status_code='APPLIED' AND applied_at IS NOT NULL AND applied_by IS NOT NULL
              AND rolled_back_at IS NULL AND rolled_back_by IS NULL)
        OR (status_code='ROLLED_BACK' AND applied_at IS NOT NULL AND applied_by IS NOT NULL
              AND rolled_back_at IS NOT NULL AND rolled_back_by IS NOT NULL))
);

CREATE TABLE registry.sample_subject_resolution_item (
    batch_id uuid NOT NULL REFERENCES registry.sample_subject_resolution_batch(batch_id),
    item_sequence integer NOT NULL CHECK (item_sequence > 0),
    source_domain varchar(30) NOT NULL CHECK (source_domain IN ('PRODUCTION','MARKET')),
    source_record_id varchar(36) NOT NULL CHECK (btrim(source_record_id) <> ''),
    expected_source_version bigint NOT NULL CHECK (expected_source_version >= 0),
    resolution_action varchar(20) NOT NULL CHECK (resolution_action IN ('LINK','VOID')),
    stable_subject_id varchar(160),
    target_sample_point_id uuid REFERENCES registry.sample_point(sample_point_id),
    reason_code varchar(80) NOT NULL CHECK (btrim(reason_code) <> ''),
    status_code varchar(20) NOT NULL CHECK (status_code IN ('STAGED','APPLIED','ROLLED_BACK')),
    before_snapshot jsonb,
    after_snapshot jsonb,
    before_sha256 char(64),
    after_sha256 char(64),
    applied_source_version bigint,
    applied_resolution_revision_id uuid,
    applied_at timestamptz,
    applied_by varchar(120),
    rolled_back_at timestamptz,
    rolled_back_by varchar(120),
    PRIMARY KEY (batch_id,item_sequence),
    UNIQUE (batch_id,source_domain,source_record_id),
    CHECK ((resolution_action='LINK' AND stable_subject_id IS NOT NULL
              AND btrim(stable_subject_id) <> '' AND target_sample_point_id IS NOT NULL)
        OR (resolution_action='VOID' AND stable_subject_id IS NULL
              AND target_sample_point_id IS NULL)),
    CHECK ((status_code='STAGED' AND before_snapshot IS NULL AND after_snapshot IS NULL
              AND before_sha256 IS NULL AND after_sha256 IS NULL
              AND applied_source_version IS NULL AND applied_resolution_revision_id IS NULL
              AND applied_at IS NULL AND applied_by IS NULL
              AND rolled_back_at IS NULL AND rolled_back_by IS NULL)
        OR (status_code='APPLIED' AND before_snapshot IS NOT NULL AND after_snapshot IS NOT NULL
              AND before_sha256 ~ '^[0-9a-f]{64}$' AND after_sha256 ~ '^[0-9a-f]{64}$'
              AND applied_source_version IS NOT NULL AND applied_resolution_revision_id IS NOT NULL
              AND applied_at IS NOT NULL AND applied_by IS NOT NULL
              AND rolled_back_at IS NULL AND rolled_back_by IS NULL)
        OR (status_code='ROLLED_BACK' AND before_snapshot IS NOT NULL AND after_snapshot IS NOT NULL
              AND before_sha256 ~ '^[0-9a-f]{64}$' AND after_sha256 ~ '^[0-9a-f]{64}$'
              AND applied_source_version IS NOT NULL AND applied_resolution_revision_id IS NOT NULL
              AND applied_at IS NOT NULL AND applied_by IS NOT NULL
              AND rolled_back_at IS NOT NULL AND rolled_back_by IS NOT NULL))
);

CREATE TABLE registry.sample_subject_resolution_revision (
    resolution_revision_id uuid PRIMARY KEY,
    source_domain varchar(30) NOT NULL CHECK (source_domain IN ('PRODUCTION','MARKET')),
    source_record_id varchar(36) NOT NULL,
    resolution_sequence bigint NOT NULL CHECK (resolution_sequence > 0),
    resolution_action varchar(20) NOT NULL CHECK (resolution_action IN ('LINK','VOID','ROLLBACK')),
    stable_subject_id varchar(160),
    target_sample_point_id uuid REFERENCES registry.sample_point(sample_point_id),
    source_version bigint NOT NULL CHECK (source_version >= 0),
    predecessor_revision_id uuid REFERENCES registry.sample_subject_resolution_revision(resolution_revision_id),
    batch_id uuid NOT NULL,
    item_sequence integer NOT NULL,
    before_sha256 char(64) NOT NULL CHECK (before_sha256 ~ '^[0-9a-f]{64}$'),
    after_sha256 char(64) NOT NULL CHECK (after_sha256 ~ '^[0-9a-f]{64}$'),
    occurred_at timestamptz NOT NULL,
    actor varchar(120) NOT NULL,
    UNIQUE (source_domain,source_record_id,resolution_sequence),
    FOREIGN KEY (batch_id,item_sequence)
      REFERENCES registry.sample_subject_resolution_item(batch_id,item_sequence),
    CHECK ((resolution_action='LINK' AND stable_subject_id IS NOT NULL
              AND target_sample_point_id IS NOT NULL)
        OR (resolution_action IN ('VOID','ROLLBACK') AND stable_subject_id IS NULL
              AND target_sample_point_id IS NULL))
);

CREATE VIEW registry.current_sample_subject_resolution AS
SELECT revision.resolution_revision_id,revision.source_domain,revision.source_record_id,
       revision.resolution_sequence,revision.resolution_action,revision.stable_subject_id,
       revision.target_sample_point_id,revision.source_version,revision.batch_id,
       revision.item_sequence,revision.before_sha256,revision.after_sha256,
       revision.occurred_at,revision.actor
FROM registry.sample_subject_resolution_revision revision
WHERE revision.resolution_sequence=(
    SELECT max(candidate.resolution_sequence)
    FROM registry.sample_subject_resolution_revision candidate
    WHERE candidate.source_domain=revision.source_domain
      AND candidate.source_record_id=revision.source_record_id)
  AND revision.resolution_action<>'ROLLBACK';

CREATE TABLE registry.sample_subject_resolution_audit (
    audit_event_id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES registry.sample_subject_resolution_batch(batch_id),
    action_code varchar(30) NOT NULL CHECK (action_code IN (
      'ITEM_APPLIED','APPLIED','APPLY_NOOP','ITEM_ROLLED_BACK','ROLLED_BACK','ROLLBACK_NOOP')),
    actor varchar(120) NOT NULL,
    occurred_at timestamptz NOT NULL,
    detail jsonb NOT NULL
);

CREATE FUNCTION registry.reject_resolution_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'resolution revisions and audit are append-only';
END;
$$;

CREATE TRIGGER sample_subject_resolution_revision_no_mutation
BEFORE UPDATE OR DELETE ON registry.sample_subject_resolution_revision
FOR EACH ROW EXECUTE FUNCTION registry.reject_resolution_append_only_mutation();

CREATE TRIGGER sample_subject_resolution_audit_no_mutation
BEFORE UPDATE OR DELETE ON registry.sample_subject_resolution_audit
FOR EACH ROW EXECUTE FUNCTION registry.reject_resolution_append_only_mutation();

CREATE FUNCTION registry.apply_sample_subject_resolution(p_batch_id uuid,p_actor varchar)
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
    existing_identity_point uuid;
    existing_point_subject varchar(160);
    before_value jsonb;
    after_value jsonb;
    before_hash char(64);
    after_hash char(64);
    revision_id uuid;
    next_sequence bigint;
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
        IF current_status<>'APPROVED' THEN
            RAISE EXCEPTION 'resolution source must be approved: %/%',item_row.source_domain,item_row.source_record_id;
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
            IF current_subject IS NOT NULL AND current_subject<>item_row.stable_subject_id THEN
                RAISE EXCEPTION 'resolution refuses to replace a different stable subject id';
            END IF;
            SELECT identity.sample_point_id INTO existing_identity_point FROM (
              SELECT legacy.sample_point_id FROM registry.sample_point_subject_identity legacy
              WHERE legacy.business_domain=item_row.source_domain
                AND legacy.subject_id=item_row.stable_subject_id
              UNION ALL
              SELECT active.target_sample_point_id FROM registry.current_sample_subject_resolution active
              WHERE active.source_domain=item_row.source_domain
                AND active.stable_subject_id=item_row.stable_subject_id
                AND active.resolution_action='LINK') identity LIMIT 1;
            IF existing_identity_point IS NOT NULL
               AND existing_identity_point<>item_row.target_sample_point_id THEN
                RAISE EXCEPTION 'stable subject id already points to another sample point';
            END IF;
            SELECT identity.subject_id INTO existing_point_subject FROM (
              SELECT legacy.subject_id FROM registry.sample_point_subject_identity legacy
              WHERE legacy.business_domain=item_row.source_domain
                AND legacy.sample_point_id=item_row.target_sample_point_id
              UNION ALL
              SELECT active.stable_subject_id FROM registry.current_sample_subject_resolution active
              WHERE active.source_domain=item_row.source_domain
                AND active.target_sample_point_id=item_row.target_sample_point_id
                AND active.resolution_action='LINK') identity LIMIT 1;
            IF existing_point_subject IS NOT NULL
               AND existing_point_subject<>item_row.stable_subject_id THEN
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

CREATE FUNCTION registry.rollback_sample_subject_resolution(p_batch_id uuid,p_actor varchar)
RETURNS varchar LANGUAGE plpgsql AS $$
DECLARE
    batch_row registry.sample_subject_resolution_batch%ROWTYPE;
    item_row registry.sample_subject_resolution_item%ROWTYPE;
    current_version bigint;
    rollback_value jsonb;
    rollback_hash char(64);
    rollback_revision_id uuid;
    applied_sequence bigint;
BEGIN
    IF p_actor IS NULL OR btrim(p_actor)='' THEN RAISE EXCEPTION 'resolution actor is required'; END IF;
    SELECT * INTO batch_row FROM registry.sample_subject_resolution_batch
    WHERE batch_id=p_batch_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'resolution batch not found'; END IF;
    IF batch_row.status_code='ROLLED_BACK' THEN
        INSERT INTO registry.sample_subject_resolution_audit
        VALUES(gen_random_uuid(),p_batch_id,'ROLLBACK_NOOP',p_actor,now(),
          jsonb_build_object('idempotencyKey',batch_row.idempotency_key));
        RETURN 'ALREADY_ROLLED_BACK';
    END IF;
    IF batch_row.status_code<>'APPLIED' THEN RAISE EXCEPTION 'resolution batch is not applied'; END IF;

    FOR item_row IN SELECT * FROM registry.sample_subject_resolution_item
        WHERE batch_id=p_batch_id ORDER BY item_sequence DESC FOR UPDATE
    LOOP
        IF item_row.source_domain='PRODUCTION' THEN
            SELECT version INTO current_version FROM production.production_record
            WHERE record_id=item_row.source_record_id FOR SHARE;
        ELSE
            SELECT version INTO current_version FROM market.market_record
            WHERE record_id=item_row.source_record_id FOR SHARE;
        END IF;
        IF current_version IS DISTINCT FROM item_row.applied_source_version THEN
            RAISE EXCEPTION 'resolution rollback source version mismatch for %/%',
              item_row.source_domain,item_row.source_record_id;
        END IF;
        IF NOT EXISTS(SELECT 1 FROM registry.current_sample_subject_resolution active
          WHERE active.resolution_revision_id=item_row.applied_resolution_revision_id) THEN
            RAISE EXCEPTION 'resolution rollback current appended revision changed for %/%',
              item_row.source_domain,item_row.source_record_id;
        END IF;
        SELECT resolution_sequence INTO applied_sequence
        FROM registry.sample_subject_resolution_revision
        WHERE resolution_revision_id=item_row.applied_resolution_revision_id;
        rollback_value:=jsonb_build_object(
          'sourceDomain',item_row.source_domain,'sourceRecordId',item_row.source_record_id,
          'sourceVersion',current_version,'resolutionAction','ROLLBACK',
          'restoredSnapshot',item_row.before_snapshot);
        rollback_hash:=encode(sha256(convert_to(rollback_value::text,'UTF8')),'hex');
        rollback_revision_id:=gen_random_uuid();
        INSERT INTO registry.sample_subject_resolution_revision(
          resolution_revision_id,source_domain,source_record_id,resolution_sequence,resolution_action,
          stable_subject_id,target_sample_point_id,source_version,predecessor_revision_id,
          batch_id,item_sequence,before_sha256,after_sha256,occurred_at,actor)
        VALUES(rollback_revision_id,item_row.source_domain,item_row.source_record_id,applied_sequence+1,
          'ROLLBACK',NULL,NULL,current_version,item_row.applied_resolution_revision_id,p_batch_id,
          item_row.item_sequence,item_row.after_sha256,rollback_hash,now(),p_actor);
        UPDATE registry.sample_subject_resolution_item SET status_code='ROLLED_BACK',
          rolled_back_at=now(),rolled_back_by=p_actor
        WHERE batch_id=p_batch_id AND item_sequence=item_row.item_sequence;
        INSERT INTO registry.sample_subject_resolution_audit
        VALUES(gen_random_uuid(),p_batch_id,'ITEM_ROLLED_BACK',p_actor,now(),jsonb_build_object(
          'itemSequence',item_row.item_sequence,'sourceDomain',item_row.source_domain,
          'sourceRecordId',item_row.source_record_id,'resolutionRevisionId',rollback_revision_id,
          'beforeSha256',item_row.after_sha256,'afterSnapshot',rollback_value,
          'afterSha256',rollback_hash));
    END LOOP;

    UPDATE registry.sample_subject_resolution_batch SET status_code='ROLLED_BACK',
      rolled_back_at=now(),rolled_back_by=p_actor WHERE batch_id=p_batch_id;
    INSERT INTO registry.sample_subject_resolution_audit
    VALUES(gen_random_uuid(),p_batch_id,'ROLLED_BACK',p_actor,now(),
      jsonb_build_object('itemCount',batch_row.expected_item_count,'inputDigest',batch_row.input_digest));
    RETURN 'ROLLED_BACK';
END;
$$;

COMMENT ON TABLE registry.sample_subject_resolution_batch IS
    'Explicit-ID-only replay boundary for EXT-007. Names, contacts, and coordinates are intentionally absent.';
COMMENT ON TABLE registry.sample_subject_resolution_item IS
    'Externally dispositioned source records with canonical before/after SHA-256 evidence.';
COMMENT ON TABLE registry.sample_subject_resolution_revision IS
    'Append-only resolution projection. Approved source facts and their optimistic versions are never rewritten.';
COMMENT ON VIEW registry.current_sample_subject_resolution IS
    'Latest active appended resolution; rollback appends a tombstone instead of deleting history.';
COMMENT ON FUNCTION registry.apply_sample_subject_resolution(uuid,varchar) IS
    'Transactional idempotent append with source-version guards and no fuzzy matching or fact mutation.';
COMMENT ON FUNCTION registry.rollback_sample_subject_resolution(uuid,varchar) IS
    'Appends a guarded rollback revision; source facts and all prior resolution revisions remain immutable.';
