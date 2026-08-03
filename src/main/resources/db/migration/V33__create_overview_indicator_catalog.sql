CREATE TABLE overview.indicator_definition (
    code varchar(80) PRIMARY KEY,
    name varchar(160) NOT NULL UNIQUE,
    unit_code varchar(40) NOT NULL,
    source_domain varchar(30) NOT NULL CHECK (source_domain IN ('PRODUCTION','MARKET','LOGISTICS','SUPPLY')),
    sort_order integer NOT NULL UNIQUE
);

INSERT INTO overview.indicator_definition(code,name,unit_code,source_domain,sort_order) VALUES
    ('PRODUCTION_CULTIVATED_AREA','核定播种面积','亩','PRODUCTION',10),
    ('PRODUCTION_ESTIMATED_OUTPUT','核定预计产量','公斤','PRODUCTION',20),
    ('MARKET_AVERAGE_TRADE_PRICE','核定平均成交价','元/吨','MARKET',30),
    ('LOGISTICS_INFLOW_VOLUME','核定物流流入量','吨','LOGISTICS',40),
    ('LOGISTICS_OUTFLOW_VOLUME','核定物流流出量','吨','LOGISTICS',50),
    ('SUPPLY_TOTAL_SUPPLY','正式供给总量','万吨','SUPPLY',60),
    ('SUPPLY_TOTAL_USE','正式使用总量','万吨','SUPPLY',70),
    ('SUPPLY_ADOPTED_ENDING_INVENTORY','正式采用期末库存','万吨','SUPPLY',80);

COMMENT ON TABLE overview.indicator_definition IS
    'Server-owned overview metric catalogue; values are aggregated from approved business facts and are never copied into this schema.';
