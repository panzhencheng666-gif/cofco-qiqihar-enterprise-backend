CREATE SCHEMA IF NOT EXISTS evidence;

CREATE TABLE evidence.evidence_photo (
    photo_id uuid PRIMARY KEY,
    state_code varchar(20) NOT NULL CHECK (state_code IN ('STAGED', 'ATTACHED')),
    original_filename varchar(255) NOT NULL CHECK (btrim(original_filename) <> ''),
    media_type varchar(30) NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    original_bytes bytea NOT NULL CHECK (octet_length(original_bytes) > 0),
    watermarked_bytes bytea NOT NULL CHECK (octet_length(watermarked_bytes) > 0),
    byte_length bigint NOT NULL CHECK (byte_length > 0 AND byte_length <= 10485760),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    captured_at timestamptz NOT NULL,
    capture_latitude numeric(10,7) NOT NULL CHECK (capture_latitude BETWEEN -90 AND 90),
    capture_longitude numeric(10,7) NOT NULL CHECK (capture_longitude BETWEEN -180 AND 180),
    watermark_text varchar(500) NOT NULL CHECK (btrim(watermark_text) <> ''),
    uploaded_by varchar(200) NOT NULL,
    uploaded_at timestamptz NOT NULL,
    attached_domain varchar(30),
    attached_record_id varchar(80),
    CHECK ((state_code = 'STAGED' AND attached_domain IS NULL AND attached_record_id IS NULL)
        OR (state_code = 'ATTACHED' AND attached_domain IS NOT NULL AND attached_record_id IS NOT NULL))
);

CREATE INDEX evidence_photo_attachment_index
    ON evidence.evidence_photo(attached_domain, attached_record_id)
    WHERE state_code = 'ATTACHED';

COMMENT ON TABLE evidence.evidence_photo IS
    'Private original and server-watermarked on-site evidence; no public object URL is stored.';
