CREATE TABLE production.supply_demand_balance (
    region_code varchar(18) NOT NULL REFERENCES platform.region(code),
    survey_year integer NOT NULL CHECK(survey_year BETWEEN 2000 AND 2100),
    product_code varchar(30) NOT NULL REFERENCES platform.product(code)
        CHECK(product_code IN ('CORN','SOYBEAN','RICE')),
    manual_values jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK(jsonb_typeof(manual_values)='object'),
    notes jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK(jsonb_typeof(notes)='object'),
    version bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
    created_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY(region_code,survey_year,product_code)
);

CREATE INDEX supply_demand_balance_summary_idx
ON production.supply_demand_balance(product_code,survey_year,region_code)
INCLUDE(manual_values,notes,version,updated_at);

CREATE TABLE production.supply_demand_balance_history (
    history_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    region_code varchar(18) NOT NULL REFERENCES platform.region(code),
    survey_year integer NOT NULL CHECK(survey_year BETWEEN 2000 AND 2100),
    product_code varchar(30) NOT NULL REFERENCES platform.product(code),
    manual_values jsonb NOT NULL CHECK(jsonb_typeof(manual_values)='object'),
    notes jsonb NOT NULL CHECK(jsonb_typeof(notes)='object'),
    source_version bigint NOT NULL CHECK(source_version >= 0),
    replaced_by varchar(120) NOT NULL,
    replaced_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX supply_demand_balance_history_key_idx
ON production.supply_demand_balance_history(
    region_code,survey_year,product_code,replaced_at DESC);

CREATE FUNCTION production.reject_supply_demand_balance_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path=pg_catalog,production
AS $$
BEGIN
    RAISE EXCEPTION 'supply demand balance history is append-only';
END;
$$;

CREATE TRIGGER supply_demand_balance_history_append_only
BEFORE UPDATE OR DELETE ON production.supply_demand_balance_history
FOR EACH ROW EXECUTE FUNCTION production.reject_supply_demand_balance_history_mutation();

COMMENT ON TABLE production.supply_demand_balance IS
    'Formal product-specific county supply-demand manual inputs. No review state exists.';
COMMENT ON TABLE production.supply_demand_balance_history IS
    'Append-only snapshots replaced by formal supply-demand balance updates.';

GRANT SELECT,INSERT,UPDATE ON TABLE production.supply_demand_balance
TO qiqihar_enterprise_runtime;
GRANT SELECT,INSERT ON TABLE production.supply_demand_balance_history
TO qiqihar_enterprise_runtime;
