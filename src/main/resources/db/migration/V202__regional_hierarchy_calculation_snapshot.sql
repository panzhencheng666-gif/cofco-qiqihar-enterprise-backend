CREATE TABLE production.regional_hierarchy_calculation (
    region_code varchar(20) NOT NULL,
    data_year integer NOT NULL,
    attempted_at timestamptz NOT NULL,
    calculated_at timestamptz,
    calculation_status varchar(40) NOT NULL,
    source_status varchar(40) NOT NULL,
    input_hash varchar(64),
    payload jsonb,
    PRIMARY KEY(region_code,data_year)
);
GRANT SELECT,INSERT,UPDATE ON production.regional_hierarchy_calculation TO qiqihar_enterprise_runtime;
