CREATE TABLE production.regional_crop_annual_stat (
    region_code varchar(18) NOT NULL REFERENCES platform.region(code),
    data_year integer NOT NULL CHECK(data_year BETWEEN 2000 AND 2100),
    product_code varchar(30) NOT NULL REFERENCES platform.product(code),
    planted_area_mu numeric(20,4) NOT NULL CHECK(planted_area_mu >= 0),
    yield_per_mu_kg numeric(20,4) NOT NULL CHECK(yield_per_mu_kg >= 0),
    total_output_kg numeric(30,4) GENERATED ALWAYS AS (
        (planted_area_mu * yield_per_mu_kg)::numeric(30,4)
    ) STORED,
    version bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
    created_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY(region_code,data_year,product_code)
);

CREATE INDEX regional_crop_annual_stat_summary_idx
ON production.regional_crop_annual_stat(product_code,data_year,region_code)
INCLUDE(planted_area_mu,yield_per_mu_kg,total_output_kg,version,updated_at);

CREATE TABLE production.regional_crop_annual_stat_history (
    history_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    region_code varchar(18) NOT NULL REFERENCES platform.region(code),
    data_year integer NOT NULL CHECK(data_year BETWEEN 2000 AND 2100),
    product_code varchar(30) NOT NULL REFERENCES platform.product(code),
    planted_area_mu numeric(20,4) NOT NULL CHECK(planted_area_mu >= 0),
    yield_per_mu_kg numeric(20,4) NOT NULL CHECK(yield_per_mu_kg >= 0),
    total_output_kg numeric(30,4) NOT NULL CHECK(total_output_kg >= 0),
    source_version bigint NOT NULL CHECK(source_version >= 0),
    replaced_by varchar(120) NOT NULL,
    replaced_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX regional_crop_annual_stat_history_key_idx
ON production.regional_crop_annual_stat_history(
    region_code,data_year,product_code,replaced_at DESC);

CREATE FUNCTION production.reject_regional_crop_annual_stat_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path=pg_catalog,production
AS $$
BEGIN
    RAISE EXCEPTION 'regional crop annual stat history is append-only';
END;
$$;

CREATE TRIGGER regional_crop_annual_stat_history_append_only
BEFORE UPDATE OR DELETE ON production.regional_crop_annual_stat_history
FOR EACH ROW EXECUTE FUNCTION production.reject_regional_crop_annual_stat_history_mutation();

COMMENT ON TABLE production.regional_crop_annual_stat IS
    'Formal county annual crop totals keyed by region, year and product. No review state exists.';
COMMENT ON TABLE production.regional_crop_annual_stat_history IS
    'Append-only snapshots replaced by updates to the formal annual crop total.';
COMMENT ON COLUMN production.regional_crop_annual_stat.total_output_kg IS
    'Server-owned generated total: planted_area_mu multiplied by yield_per_mu_kg.';

GRANT SELECT,INSERT,UPDATE ON TABLE production.regional_crop_annual_stat
TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT ON TABLE production.regional_crop_annual_stat_history
TO qiqihar_enterprise_runtime;
