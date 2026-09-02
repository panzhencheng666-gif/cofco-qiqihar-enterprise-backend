ALTER TABLE platform.import_job
    DROP CONSTRAINT import_job_domain_code_check,
    ADD CONSTRAINT import_job_domain_code_check
        CHECK (domain_code IN (
            'PRODUCTION', 'MARKET', 'LOGISTICS',
            'DESIGN_SAMPLE_POINT', 'FORMAL_SAMPLE_POINT'));
