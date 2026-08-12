CREATE TABLE platform.data_lifecycle_policy (
    data_class varchar(60) PRIMARY KEY,
    classification varchar(20) NOT NULL CHECK (classification IN ('INTERNAL','SENSITIVE')),
    retention_days integer CHECK (retention_days IS NULL OR retention_days > 0),
    disposal_mode varchar(40) NOT NULL CHECK (disposal_mode IN ('REVIEW_THEN_DELETE','KEEP_WHILE_ACTIVE')),
    nonproduction_control varchar(60) NOT NULL,
    responsible_role varchar(100) NOT NULL,
    legal_hold_supported boolean NOT NULL,
    governance_state varchar(20) NOT NULL CHECK (governance_state IN ('ENFORCED','DRAFT')),
    policy_version varchar(40) NOT NULL,
    CHECK (btrim(nonproduction_control) <> '' AND btrim(responsible_role) <> '')
);

INSERT INTO platform.data_lifecycle_policy(
  data_class,classification,retention_days,disposal_mode,nonproduction_control,
  responsible_role,legal_hold_supported,governance_state,policy_version)
VALUES
  ('EVIDENCE_PHOTO','SENSITIVE',1825,'REVIEW_THEN_DELETE','NO_COPY_OR_STABLE_PSEUDONYM',
   'DATA_PROTECTION_OWNER',true,'ENFORCED','DATA-LIFECYCLE-2026.08'),
  ('SECURITY_IDENTITY','SENSITIVE',3650,'REVIEW_THEN_DELETE','STABLE_PSEUDONYM',
   'IDENTITY_GOVERNANCE_OWNER',true,'ENFORCED','DATA-LIFECYCLE-2026.08'),
  ('STABLE_SUBJECT_IDENTITY','SENSITIVE',NULL,'KEEP_WHILE_ACTIVE','STABLE_PSEUDONYM',
   'MASTER_DATA_OWNER',true,'ENFORCED','DATA-LIFECYCLE-2026.08');

COMMENT ON TABLE platform.data_lifecycle_policy IS
    'Versioned technical defaults for masking, retention and destruction review; candidates are never deleted automatically.';

CREATE TABLE platform.data_legal_hold (
    hold_id uuid PRIMARY KEY,
    resource_type varchar(60) NOT NULL,
    resource_id varchar(240) NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    placed_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    placed_at timestamptz NOT NULL,
    released_by varchar(120) REFERENCES platform.security_user(subject_id),
    released_at timestamptz,
    CHECK ((released_at IS NULL AND released_by IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND released_at >= placed_at))
);

CREATE UNIQUE INDEX data_legal_hold_one_active
    ON platform.data_legal_hold(resource_type,resource_id) WHERE released_at IS NULL;

CREATE TABLE platform.data_legal_hold_event (
    event_id bigserial PRIMARY KEY,
    hold_id uuid NOT NULL,
    action_code varchar(20) NOT NULL CHECK (action_code IN ('PLACED','RELEASED')),
    event_at timestamptz NOT NULL,
    actor_subject_id varchar(120) NOT NULL,
    snapshot jsonb NOT NULL
);

CREATE FUNCTION platform.audit_data_legal_hold()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND (OLD.released_at IS NOT NULL OR NEW.hold_id<>OLD.hold_id
       OR NEW.resource_type<>OLD.resource_type OR NEW.resource_id<>OLD.resource_id
       OR NEW.reason<>OLD.reason OR NEW.placed_by<>OLD.placed_by OR NEW.placed_at<>OLD.placed_at
       OR NEW.released_at IS NULL) THEN
        RAISE EXCEPTION 'legal hold may only be released once';
    END IF;
    INSERT INTO platform.data_legal_hold_event(
      hold_id,action_code,event_at,actor_subject_id,snapshot)
    VALUES(NEW.hold_id,CASE WHEN TG_OP='INSERT' THEN 'PLACED' ELSE 'RELEASED' END,
      CASE WHEN TG_OP='INSERT' THEN NEW.placed_at ELSE NEW.released_at END,
      CASE WHEN TG_OP='INSERT' THEN NEW.placed_by ELSE NEW.released_by END,to_jsonb(NEW));
    RETURN NEW;
END;
$$;

CREATE TRIGGER data_legal_hold_audit
AFTER INSERT OR UPDATE ON platform.data_legal_hold
FOR EACH ROW EXECUTE FUNCTION platform.audit_data_legal_hold();

CREATE FUNCTION platform.reject_data_legal_hold_destruction()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'legal holds may only be released and are never deleted';
END;
$$;
CREATE TRIGGER data_legal_hold_immutable
BEFORE DELETE ON platform.data_legal_hold
FOR EACH ROW EXECUTE FUNCTION platform.reject_data_legal_hold_destruction();

CREATE FUNCTION platform.reject_data_legal_hold_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'legal hold events are append-only';
END;
$$;
CREATE TRIGGER data_legal_hold_event_immutable
BEFORE UPDATE OR DELETE ON platform.data_legal_hold_event
FOR EACH ROW EXECUTE FUNCTION platform.reject_data_legal_hold_event_mutation();

CREATE VIEW platform.security_user_nonproduction_masked AS
SELECT encode(sha256(convert_to(subject_id,'UTF8')),'hex') AS source_fingerprint,
       'employee-' || substr(encode(sha256(convert_to(subject_id,'UTF8')),'hex'),1,12) AS masked_subject_id,
       '测试员工-' || substr(encode(sha256(convert_to(display_name || ':' || subject_id,'UTF8')),'hex'),1,8)
           AS masked_display_name,
       work_unit_code,enabled
FROM platform.security_user;

COMMENT ON VIEW platform.security_user_nonproduction_masked IS
    'Deterministic non-production identity projection; source identifiers and names are never exposed.';

ALTER TABLE evidence.evidence_photo ADD COLUMN watermarked_sha256 char(64)
    GENERATED ALWAYS AS (encode(sha256(watermarked_bytes),'hex')) STORED;
ALTER TABLE evidence.evidence_photo
    ALTER COLUMN watermarked_sha256 SET NOT NULL,
    ADD CONSTRAINT evidence_photo_watermarked_digest_shape
      CHECK (watermarked_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT evidence_photo_content_digest_check CHECK (
      octet_length(original_bytes)=byte_length
      AND encode(sha256(original_bytes),'hex')=btrim(sha256)
      AND encode(sha256(watermarked_bytes),'hex')=btrim(watermarked_sha256));

CREATE VIEW evidence.evidence_photo_consistency AS
SELECT photo.photo_id,
       octet_length(photo.original_bytes)=photo.byte_length AS original_length_matches,
       encode(sha256(photo.original_bytes),'hex')=btrim(photo.sha256) AS original_digest_matches,
       encode(sha256(photo.watermarked_bytes),'hex')=btrim(photo.watermarked_sha256)
         AS watermarked_digest_matches,
       CASE
         WHEN photo.state_code='STAGED' THEN photo.attached_domain IS NULL
           AND photo.attached_record_id IS NULL AND photo.attached_region_code IS NULL
         WHEN photo.attached_domain='PRODUCTION' THEN EXISTS(
           SELECT 1 FROM production.production_record record
           WHERE record.record_id=photo.attached_record_id AND record.region_code=photo.attached_region_code)
         WHEN photo.attached_domain='MARKET' THEN EXISTS(
           SELECT 1 FROM market.market_record record
           WHERE record.record_id=photo.attached_record_id AND record.region_code=photo.attached_region_code)
         ELSE false
       END AS attachment_reference_matches,
       CASE WHEN octet_length(photo.original_bytes)=photo.byte_length
          AND encode(sha256(photo.original_bytes),'hex')=btrim(photo.sha256)
          AND encode(sha256(photo.watermarked_bytes),'hex')=btrim(photo.watermarked_sha256)
          AND (photo.state_code='STAGED'
            OR (photo.attached_domain='PRODUCTION' AND EXISTS(
              SELECT 1 FROM production.production_record record
              WHERE record.record_id=photo.attached_record_id
                AND record.region_code=photo.attached_region_code))
            OR (photo.attached_domain='MARKET' AND EXISTS(
              SELECT 1 FROM market.market_record record
              WHERE record.record_id=photo.attached_record_id
                AND record.region_code=photo.attached_region_code)))
         THEN 'CONSISTENT' ELSE 'INCONSISTENT' END AS consistency_state
FROM evidence.evidence_photo photo;

CREATE FUNCTION evidence.reject_held_photo_destruction()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM platform.data_legal_hold hold
      WHERE hold.resource_type='EVIDENCE_PHOTO' AND hold.resource_id=OLD.photo_id::text
        AND hold.released_at IS NULL) THEN
        RAISE EXCEPTION 'evidence photo has an active legal hold';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER evidence_photo_legal_hold
BEFORE DELETE ON evidence.evidence_photo
FOR EACH ROW EXECUTE FUNCTION evidence.reject_held_photo_destruction();

CREATE VIEW evidence.evidence_photo_retention_candidate AS
SELECT photo.photo_id,photo.state_code,photo.uploaded_at,
       policy.retention_days,photo.uploaded_at + make_interval(days=>policy.retention_days) AS eligible_after,
       'REVIEW_REQUIRED'::varchar AS disposition_state
FROM evidence.evidence_photo photo
JOIN platform.data_lifecycle_policy policy ON policy.data_class='EVIDENCE_PHOTO'
WHERE policy.governance_state='ENFORCED' AND policy.disposal_mode='REVIEW_THEN_DELETE'
  AND photo.uploaded_at + make_interval(days=>policy.retention_days) <= now()
  AND NOT EXISTS(SELECT 1 FROM platform.data_legal_hold hold
    WHERE hold.resource_type='EVIDENCE_PHOTO' AND hold.resource_id=photo.photo_id::text
      AND hold.released_at IS NULL);

COMMENT ON VIEW evidence.evidence_photo_retention_candidate IS
    'Review-only destruction candidates after retention; this view never performs deletion.';
