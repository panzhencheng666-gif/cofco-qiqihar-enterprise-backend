CREATE TABLE production.regional_estimate_batch (
    root_region_code varchar(20) NOT NULL,
    data_year integer NOT NULL CHECK(data_year BETWEEN 2001 AND 2100),
    batch_day date NOT NULL,
    calculated_at timestamptz,
    attempted_at timestamptz NOT NULL,
    input_hash text NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY(root_region_code,data_year,batch_day)
);
GRANT SELECT,INSERT,UPDATE ON production.regional_estimate_batch TO qiqihar_enterprise_runtime;
