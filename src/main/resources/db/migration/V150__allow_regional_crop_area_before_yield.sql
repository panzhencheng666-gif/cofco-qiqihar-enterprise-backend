ALTER TABLE production.regional_crop_annual_stat
    ALTER COLUMN yield_per_mu_kg DROP NOT NULL;

ALTER TABLE production.regional_crop_annual_stat_history
    ALTER COLUMN yield_per_mu_kg DROP NOT NULL,
    ALTER COLUMN total_output_kg DROP NOT NULL;

COMMENT ON COLUMN production.regional_crop_annual_stat.yield_per_mu_kg IS
    'Nullable until the annual yield is formally reported for the county.';
COMMENT ON COLUMN production.regional_crop_annual_stat.total_output_kg IS
    'Server-owned generated total; null until yield_per_mu_kg is reported.';
COMMENT ON COLUMN production.regional_crop_annual_stat_history.yield_per_mu_kg IS
    'Historical yield snapshot; null when only planted area had been reported.';
COMMENT ON COLUMN production.regional_crop_annual_stat_history.total_output_kg IS
    'Historical generated total snapshot; null when yield was not yet reported.';
