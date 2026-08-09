ALTER TABLE platform.import_job
    ADD COLUMN lease_token uuid NULL,
    ADD COLUMN lease_until timestamptz NULL;

ALTER TABLE platform.import_job
    DROP CONSTRAINT import_job_lifecycle_check,
    ADD CONSTRAINT import_job_lifecycle_check CHECK (
        (status_code = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL
            AND lease_token IS NULL AND lease_until IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status_code = 'PROCESSING' AND started_at IS NOT NULL AND completed_at IS NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status_code IN ('COMPLETED', 'COMPLETED_WITH_ERRORS') AND completed_at IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status_code = 'FAILED' AND completed_at IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL
            AND failure_code IS NOT NULL AND failure_message IS NOT NULL));

CREATE INDEX import_job_expired_lease
    ON platform.import_job(lease_until, import_job_id)
    WHERE status_code = 'PROCESSING';

COMMENT ON COLUMN platform.import_job.lease_token IS
    'Opaque claim token preventing a stale or replaced worker from completing the same import job.';
