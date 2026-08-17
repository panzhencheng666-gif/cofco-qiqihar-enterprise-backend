-- Expand the shared import ledger with a governed, incomplete-data-safe draft layer.
-- Canonical production, market, and logistics tables retain their existing invariants.
CREATE TABLE platform.business_import_draft (
    import_draft_id uuid PRIMARY KEY,
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('PRODUCTION', 'MARKET', 'LOGISTICS')),
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    object_type_code varchar(60) NULL REFERENCES platform.object_type(code),
    sample_name varchar(200) NOT NULL CHECK (btrim(sample_name) <> ''),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    survey_period varchar(60) NULL,
    values_json jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(values_json) = 'object'),
    missing_fields_json jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(missing_fields_json) = 'array'),
    completeness_percent smallint NOT NULL DEFAULT 0
        CHECK (completeness_percent BETWEEN 0 AND 100),
    state_code varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (state_code IN ('DRAFT', 'PROMOTED', 'DISCARDED')),
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    import_job_id uuid NOT NULL REFERENCES platform.import_job(import_job_id) ON DELETE RESTRICT,
    source_row_number integer NOT NULL CHECK (source_row_number > 1),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    canonical_record_id varchar(120) NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (import_job_id, source_row_number),
    CHECK (object_type_code IS NULL OR btrim(object_type_code) <> ''),
    CHECK (survey_period IS NULL OR btrim(survey_period) <> ''),
    CHECK ((state_code = 'PROMOTED' AND canonical_record_id IS NOT NULL)
        OR (state_code <> 'PROMOTED' AND canonical_record_id IS NULL))
);

CREATE INDEX business_import_draft_requester_query
    ON platform.business_import_draft(created_by, state_code, updated_at DESC);
CREATE INDEX business_import_draft_region_query
    ON platform.business_import_draft(region_code, domain_code, state_code, updated_at DESC);

CREATE TABLE platform.import_job_photo (
    import_job_id uuid NOT NULL REFERENCES platform.import_job(import_job_id) ON DELETE RESTRICT,
    photo_id uuid NOT NULL REFERENCES evidence.evidence_photo(photo_id) ON DELETE RESTRICT,
    original_filename varchar(255) NOT NULL CHECK (btrim(original_filename) <> ''),
    normalized_filename varchar(255) NOT NULL CHECK (btrim(normalized_filename) <> ''),
    captured_at timestamptz NULL,
    capture_latitude numeric(10,7) NULL CHECK (capture_latitude BETWEEN -90 AND 90),
    capture_longitude numeric(10,7) NULL CHECK (capture_longitude BETWEEN -180 AND 180),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (import_job_id, photo_id),
    UNIQUE (import_job_id, normalized_filename)
);

CREATE TABLE platform.business_import_draft_evidence (
    import_draft_id uuid NOT NULL
        REFERENCES platform.business_import_draft(import_draft_id) ON DELETE RESTRICT,
    photo_id uuid NOT NULL REFERENCES evidence.evidence_photo(photo_id) ON DELETE RESTRICT,
    sort_order smallint NOT NULL CHECK (sort_order BETWEEN 1 AND 5),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (import_draft_id, photo_id),
    UNIQUE (import_draft_id, sort_order),
    UNIQUE (photo_id)
);

ALTER TABLE platform.import_row_result
    ADD COLUMN import_draft_id uuid NULL
        REFERENCES platform.business_import_draft(import_draft_id) ON DELETE RESTRICT,
    ADD COLUMN warning_code varchar(100) NULL,
    ADD COLUMN warning_message varchar(500) NULL,
    DROP CONSTRAINT import_row_result_check,
    ADD CONSTRAINT import_row_result_check CHECK (
        (outcome_code = 'IMPORTED'
            AND error_code IS NULL AND error_message IS NULL
            AND ((business_record_id IS NOT NULL AND import_draft_id IS NULL)
              OR (business_record_id IS NULL AND import_draft_id IS NOT NULL)))
        OR (outcome_code = 'ERROR'
            AND error_code IS NOT NULL AND error_message IS NOT NULL
            AND business_record_id IS NULL AND import_draft_id IS NULL
            AND warning_code IS NULL AND warning_message IS NULL)),
    ADD CONSTRAINT import_row_result_warning_check CHECK (
        (warning_code IS NULL AND warning_message IS NULL)
        OR (outcome_code = 'IMPORTED' AND warning_code IS NOT NULL AND warning_message IS NOT NULL));

CREATE INDEX import_row_result_draft_query
    ON platform.import_row_result(import_draft_id)
    WHERE import_draft_id IS NOT NULL;

-- Batch photos may legitimately have no EXIF capture time or location. Their
-- absence is a completeness warning, never a reason to reject the business row.
DROP VIEW evidence.evidence_photo_consistency;

ALTER TABLE evidence.evidence_photo
    ALTER COLUMN captured_at DROP NOT NULL,
    ALTER COLUMN capture_latitude DROP NOT NULL,
    ALTER COLUMN capture_longitude DROP NOT NULL;

CREATE VIEW evidence.evidence_photo_consistency AS
SELECT photo.photo_id,
       CASE WHEN photo.content_storage_code='DATABASE'
         THEN octet_length(photo.original_bytes)=photo.byte_length END AS original_length_matches,
       CASE WHEN photo.content_storage_code='DATABASE'
         THEN encode(sha256(photo.original_bytes),'hex')=btrim(photo.sha256) END AS original_digest_matches,
       CASE WHEN photo.content_storage_code='DATABASE'
         THEN encode(sha256(photo.watermarked_bytes),'hex')=btrim(photo.watermarked_sha256)
         END AS watermarked_digest_matches,
       CASE
         WHEN photo.state_code='STAGED' THEN photo.attached_domain IS NULL
           AND photo.attached_record_id IS NULL AND photo.attached_region_code IS NULL
         WHEN photo.attached_domain='PRODUCTION' THEN EXISTS(
           SELECT 1 FROM production.production_record record
           WHERE record.record_id=photo.attached_record_id AND record.region_code=photo.attached_region_code)
         WHEN photo.attached_domain='MARKET' THEN EXISTS(
           SELECT 1 FROM market.market_record record
           WHERE record.record_id=photo.attached_record_id AND record.region_code=photo.attached_region_code)
         WHEN photo.attached_domain='LOGISTICS' THEN EXISTS(
           SELECT 1 FROM logistics.route_event event
           WHERE event.event_id::text=photo.attached_record_id
             AND event.business_region_code=photo.attached_region_code)
         ELSE false
       END AS attachment_reference_matches,
       CASE
         WHEN photo.content_storage_code='EXTERNAL' THEN 'EXTERNAL_VERIFICATION_REQUIRED'
         WHEN octet_length(photo.original_bytes)=photo.byte_length
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
                AND record.region_code=photo.attached_region_code))
            OR (photo.attached_domain='LOGISTICS' AND EXISTS(
              SELECT 1 FROM logistics.route_event event
              WHERE event.event_id::text=photo.attached_record_id
                AND event.business_region_code=photo.attached_region_code)))
         THEN 'CONSISTENT' ELSE 'INCONSISTENT' END AS consistency_state
FROM evidence.evidence_photo photo;

GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE
    platform.business_import_draft,
    platform.business_import_draft_evidence,
    platform.import_job_photo
TO qiqihar_enterprise_runtime;

COMMENT ON TABLE platform.business_import_draft IS
    'Governed incomplete business rows imported from the nine product workbooks before canonical promotion.';
COMMENT ON TABLE platform.business_import_draft_evidence IS
    'At most five ordered, optional private photos associated with one import draft.';
COMMENT ON COLUMN platform.import_row_result.warning_code IS
    'Non-blocking import warning; an IMPORTED row remains successful when optional photo handling is skipped.';
