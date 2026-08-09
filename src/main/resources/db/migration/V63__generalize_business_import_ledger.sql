-- The import ledger is shared by the three business submission domains.  A row result
-- records the created business record rather than assuming production-only storage.
ALTER TABLE platform.import_job
    DROP CONSTRAINT import_job_domain_code_check,
    ADD CONSTRAINT import_job_domain_code_check
        CHECK (domain_code IN ('PRODUCTION', 'MARKET', 'LOGISTICS'));

ALTER TABLE platform.import_row_result
    RENAME COLUMN production_record_id TO business_record_id;
