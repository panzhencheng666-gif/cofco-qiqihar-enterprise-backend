ALTER TABLE platform.import_job
    DROP CONSTRAINT IF EXISTS import_job_status_code_check;

ALTER TABLE platform.import_job
    ALTER COLUMN completed_at DROP NOT NULL,
    ADD COLUMN started_at timestamptz NULL,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN failure_code varchar(100) NULL,
    ADD COLUMN failure_message varchar(500) NULL,
    ADD CONSTRAINT import_job_status_code_check CHECK (
        status_code IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED')),
    ADD CONSTRAINT import_job_lifecycle_check CHECK (
        (status_code = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status_code = 'PROCESSING' AND started_at IS NOT NULL AND completed_at IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status_code IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') AND completed_at IS NOT NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status_code = 'FAILED' AND completed_at IS NOT NULL
            AND failure_code IS NOT NULL AND failure_message IS NOT NULL));

CREATE INDEX import_job_queue_claim
    ON platform.import_job(status_code, created_at, import_job_id)
    WHERE status_code IN ('QUEUED', 'PROCESSING');

COMMENT ON COLUMN platform.import_job.attempt_count IS
    'Number of durable background-processing claims. Used for recovery and operational diagnosis.';
