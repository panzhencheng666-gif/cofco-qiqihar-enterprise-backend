-- Additive structures in the current database. This does not move a database,
-- change its address, or copy it to another instance.

ALTER TABLE platform.design_sample_point
    ADD COLUMN lifecycle_status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle_status IN ('ACTIVE','EXPIRED')),
    ADD COLUMN expired_at timestamptz,
    ADD COLUMN detailed_address varchar(500),
    ADD COLUMN assignment_run_id uuid,
    ADD COLUMN coordinate_seed varchar(160),
    ADD CONSTRAINT design_sample_expiry_state CHECK (
        (lifecycle_status='ACTIVE' AND expired_at IS NULL)
        OR (lifecycle_status='EXPIRED' AND expired_at IS NOT NULL));

CREATE INDEX design_sample_point_active_village_lookup
    ON platform.design_sample_point(region_code,updated_at DESC,design_sample_point_id)
    WHERE lifecycle_status='ACTIVE';

DROP INDEX platform.design_sample_point_business_identity;
CREATE UNIQUE INDEX design_sample_point_business_identity
    ON platform.design_sample_point(
        domain_code,product_code,object_type_code,region_code,lower(btrim(sample_name)))
    WHERE lifecycle_status='ACTIVE';

CREATE FUNCTION platform.enforce_one_active_design_sample_per_village()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.lifecycle_status='ACTIVE'
       AND (SELECT administrative_level FROM platform.region WHERE code=NEW.region_code)='VILLAGE'
       AND EXISTS(SELECT 1 FROM platform.design_sample_point other
                  WHERE other.region_code=NEW.region_code
                    AND other.lifecycle_status='ACTIVE'
                    AND other.design_sample_point_id<>NEW.design_sample_point_id) THEN
        RAISE EXCEPTION 'only one active design sample point is allowed per village'
            USING ERRCODE='23505',CONSTRAINT='design_sample_one_active_per_village';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER design_sample_one_active_per_village
    BEFORE INSERT OR UPDATE OF region_code,lifecycle_status ON platform.design_sample_point
    FOR EACH ROW EXECUTE FUNCTION platform.enforce_one_active_design_sample_per_village();

CREATE TABLE platform.user_map_annotation (
    subject_id varchar(120) PRIMARY KEY REFERENCES platform.security_user(subject_id),
    annotation_type varchar(20) NOT NULL CHECK (annotation_type IN ('POINT','RECTANGLE')),
    geometry geometry(Geometry,4326) NOT NULL,
    region_code varchar(12) REFERENCES platform.region(code),
    administrative_level varchar(20),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (GeometryType(geometry) IN ('POINT','POLYGON')),
    CHECK (ST_SRID(geometry)=4326 AND ST_IsValid(geometry) AND NOT ST_IsEmpty(geometry)),
    CHECK ((annotation_type='POINT' AND GeometryType(geometry)='POINT')
        OR (annotation_type='RECTANGLE' AND GeometryType(geometry)='POLYGON')),
    CHECK (updated_at >= created_at)
);
CREATE INDEX user_map_annotation_geometry_gix
    ON platform.user_map_annotation USING gist(geometry);

CREATE TABLE platform.email_identity (
    email_normalized varchar(320) PRIMARY KEY,
    subject_id varchar(120) NOT NULL UNIQUE REFERENCES platform.security_user(subject_id),
    email_display varchar(320) NOT NULL,
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (email_normalized=lower(btrim(email_normalized))),
    CHECK (email_normalized ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'),
    CHECK (updated_at >= created_at)
);

CREATE TABLE platform.email_challenge (
    challenge_id uuid PRIMARY KEY,
    email_normalized varchar(320) NOT NULL,
    purpose varchar(20) NOT NULL CHECK (purpose IN ('LOGIN','REGISTER','BIND')),
    session_hash char(64) NOT NULL CHECK (session_hash ~ '^[a-f0-9]{64}$'),
    client_hash char(64) NOT NULL CHECK (client_hash ~ '^[a-f0-9]{64}$'),
    code_digest char(64) NOT NULL CHECK (code_digest ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    sent_at timestamptz,
    consumed_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX email_challenge_address_time
    ON platform.email_challenge(email_normalized,created_at DESC);
CREATE INDEX email_challenge_client_time
    ON platform.email_challenge(client_hash,created_at DESC);

CREATE TABLE platform.private_message (
    message_id uuid PRIMARY KEY,
    sender_subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    recipient_subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    channel varchar(20) NOT NULL CHECK (channel IN ('STATION','EMAIL')),
    title varchar(200) NOT NULL CHECK (btrim(title) <> ''),
    body text NOT NULL CHECK (btrim(body) <> ''),
    recipient_address varchar(320),
    idempotency_key varchar(160) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sender_subject_id,idempotency_key),
    CHECK ((channel='STATION' AND recipient_address IS NULL)
        OR (channel='EMAIL' AND recipient_address IS NOT NULL))
);
CREATE INDEX private_message_inbox_lookup
    ON platform.private_message(recipient_subject_id,created_at DESC,message_id)
    WHERE channel='STATION';
CREATE INDEX private_message_sent_lookup
    ON platform.private_message(sender_subject_id,created_at DESC,message_id);

CREATE TABLE platform.private_message_receipt (
    message_id uuid PRIMARY KEY REFERENCES platform.private_message(message_id),
    recipient_subject_id varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    read_at timestamptz
);
CREATE INDEX private_message_unread_lookup
    ON platform.private_message_receipt(recipient_subject_id,message_id)
    WHERE read_at IS NULL;

CREATE TABLE platform.message_email_delivery (
    message_id uuid PRIMARY KEY REFERENCES platform.private_message(message_id),
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SENDING','SENT','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    sent_at timestamptz,
    last_error_code varchar(80),
    locked_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX message_email_delivery_work
    ON platform.message_email_delivery(status,next_attempt_at,message_id)
    WHERE status IN ('PENDING','FAILED','SENDING');

CREATE FUNCTION platform.reject_private_message_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'private messages and delivery records are permanent';
END;
$$;
CREATE TRIGGER private_message_no_delete
    BEFORE DELETE ON platform.private_message
    FOR EACH ROW EXECUTE FUNCTION platform.reject_private_message_delete();
CREATE TRIGGER private_message_receipt_no_delete
    BEFORE DELETE ON platform.private_message_receipt
    FOR EACH ROW EXECUTE FUNCTION platform.reject_private_message_delete();
CREATE TRIGGER message_email_delivery_no_delete
    BEFORE DELETE ON platform.message_email_delivery
    FOR EACH ROW EXECUTE FUNCTION platform.reject_private_message_delete();

ALTER TABLE platform.user_map_annotation OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.email_identity OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.email_challenge OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.private_message OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.private_message_receipt OWNER TO qiqihar_migration_owner;
ALTER TABLE platform.message_email_delivery OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.reject_private_message_delete() OWNER TO qiqihar_migration_owner;
ALTER FUNCTION platform.enforce_one_active_design_sample_per_village() OWNER TO qiqihar_migration_owner;

REVOKE ALL ON TABLE platform.user_map_annotation FROM PUBLIC;
REVOKE ALL ON TABLE platform.email_identity FROM PUBLIC;
REVOKE ALL ON TABLE platform.email_challenge FROM PUBLIC;
REVOKE ALL ON TABLE platform.private_message FROM PUBLIC;
REVOKE ALL ON TABLE platform.private_message_receipt FROM PUBLIC;
REVOKE ALL ON TABLE platform.message_email_delivery FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform.user_map_annotation TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON platform.email_identity,platform.email_challenge TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT,UPDATE ON platform.private_message,platform.private_message_receipt,
    platform.message_email_delivery TO qiqihar_enterprise_runtime;

COMMENT ON TABLE platform.user_map_annotation IS
    'Exactly one durable point or rectangle for each subject; application queries must bind the authenticated subject.';
COMMENT ON TABLE platform.private_message IS
    'Permanent user-composed station or email content; rows cannot be deleted.';
COMMENT ON TABLE platform.email_challenge IS
    'Five-minute, single-use email verification metadata; raw verification codes are never stored.';
