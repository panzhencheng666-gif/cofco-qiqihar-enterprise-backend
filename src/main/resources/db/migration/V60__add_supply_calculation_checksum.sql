ALTER TABLE supply.calculation_run
    ADD COLUMN calculation_checksum char(64);

COMMENT ON COLUMN supply.calculation_run.calculation_checksum IS
    'SHA-256 of the immutable input-set provenance, formula snapshot, and calculated values; NULL only for pre-V60 historical read-only runs.';
