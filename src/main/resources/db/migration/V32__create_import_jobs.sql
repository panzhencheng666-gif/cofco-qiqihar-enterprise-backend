-- Shared import ledger. Every source file is retained only for controlled retry and each row has a durable outcome.
CREATE TABLE platform.import_job (
    import_job_id uuid PRIMARY KEY,
    domain_code varchar(40) NOT NULL CHECK (domain_code IN ('PRODUCTION')),
    idempotency_key varchar(128) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    content_sha256 char(64) NOT NULL,
    source_content text NOT NULL,
    requested_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    work_unit_code varchar(60) NOT NULL REFERENCES platform.work_unit(code),
    retry_of_import_job_id uuid NULL REFERENCES platform.import_job(import_job_id),
    status_code varchar(40) NOT NULL CHECK (status_code IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')),
    created_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    UNIQUE (requested_by, domain_code, idempotency_key)
);

CREATE TABLE platform.import_row_result (
    import_job_id uuid NOT NULL REFERENCES platform.import_job(import_job_id) ON DELETE RESTRICT,
    row_number integer NOT NULL CHECK (row_number > 1),
    outcome_code varchar(20) NOT NULL CHECK (outcome_code IN ('IMPORTED', 'ERROR')),
    error_code varchar(100) NULL,
    error_message varchar(500) NULL,
    production_record_id varchar(120) NULL,
    row_data jsonb NOT NULL,
    PRIMARY KEY (import_job_id, row_number),
    CHECK ((outcome_code = 'IMPORTED' AND error_code IS NULL AND error_message IS NULL AND production_record_id IS NOT NULL)
        OR (outcome_code = 'ERROR' AND error_code IS NOT NULL AND error_message IS NOT NULL AND production_record_id IS NULL))
);

CREATE INDEX import_job_requester_query ON platform.import_job(requested_by, domain_code, created_at DESC);
CREATE INDEX import_row_result_error_query ON platform.import_row_result(import_job_id, outcome_code, row_number);

COMMENT ON TABLE platform.import_job IS
    'Idempotent business-file import job. Source content is retained exclusively for an authenticated retry flow.';
