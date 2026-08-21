-- DEF-156/157: keep legacy route mechanics for historical replay while making
-- the public logistics survey contract independent from monitoring periods and nodes.
ALTER TABLE logistics.route_event
    ADD COLUMN business_region_code varchar(12) REFERENCES platform.region(code),
    ADD COLUMN reporter_phone varchar(80),
    ADD COLUMN sample_contact varchar(80),
    ADD COLUMN sample_latitude numeric(9,6),
    ADD COLUMN sample_longitude numeric(10,6);

UPDATE logistics.route_event
SET business_region_code = CASE direction_code
        WHEN 'INFLOW' THEN destination_region_code
        ELSE origin_region_code
    END;

ALTER TABLE logistics.route_event
    ALTER COLUMN monitoring_period_code DROP NOT NULL,
    ALTER COLUMN origin_node_code DROP NOT NULL,
    ALTER COLUMN destination_node_code DROP NOT NULL;

ALTER TABLE logistics.route_event
    ADD CONSTRAINT route_event_sample_latitude_valid
        CHECK (sample_latitude IS NULL OR sample_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT route_event_sample_longitude_valid
        CHECK (sample_longitude IS NULL OR sample_longitude BETWEEN -180 AND 180);

ALTER TABLE logistics.route_fact DROP CONSTRAINT route_fact_fact_code_check;
ALTER TABLE logistics.route_fact ADD CONSTRAINT route_fact_fact_code_check
    CHECK (fact_code IN ('ROUTE_VOLUME','FREIGHT_RATE','TRANSIT_TIME','BOARD_PRICE'));

ALTER TABLE platform.logistics_core_field_definition
    DROP CONSTRAINT logistics_core_field_definition_control_type_check;
ALTER TABLE platform.logistics_core_field_definition
    ADD CONSTRAINT logistics_core_field_definition_control_type_check
        CHECK (control_type IN ('SELECT','DATE','DECIMAL','TEXT','READONLY_DATE',
            'READONLY_TEXT','READONLY_DATETIME','READONLY_STATUS'));

-- Move the historical definitions out of the public ordering range. They stay
-- stored and readable in the database, but no longer participate in public applicability.
UPDATE platform.logistics_core_field_definition SET sort_order=sort_order+1000;
DELETE FROM platform.logistics_core_field_applicability;

UPDATE platform.logistics_core_field_definition
SET label='填报人',control_type='READONLY_TEXT',binding='READONLY.reporter',option_source=NULL,
    unit=NULL,decimal_precision=NULL,decimal_scale=NULL,required=false,sort_order=60
WHERE code='LOG_REPORTER';
UPDATE platform.logistics_core_field_definition
SET label='运输方式',sort_order=110 WHERE code='LOG_TRANSPORT_MODE';
UPDATE platform.logistics_core_field_definition
SET label='运输方向',sort_order=120 WHERE code='LOG_DIRECTION';
UPDATE platform.logistics_core_field_definition
SET label='运输数量',sort_order=130 WHERE code='LOG_ROUTE_VOLUME';
UPDATE platform.logistics_core_field_definition
SET label='物流运价（不含车板价）',sort_order=140 WHERE code='LOG_FREIGHT_RATE';
UPDATE platform.logistics_core_field_definition
SET label='填报状态',sort_order=160 WHERE code='LOG_STATUS';

INSERT INTO platform.logistics_core_field_definition(
    code,label,control_type,binding,option_source,unit,decimal_precision,decimal_scale,required,sort_order)
VALUES
 ('surveyYear','数据年份','DECIMAL','EVENT.survey_year',NULL,NULL,4,0,true,10),
 ('surveyMonth','数据月份','DECIMAL','EVENT.survey_month',NULL,NULL,2,0,false,20),
 ('fillingDate','填报日期','READONLY_DATE','READONLY.created_at',NULL,NULL,NULL,NULL,false,30),
 ('LOG_SAMPLE_NAME','物流样本点名称','TEXT','EVENT.source_organization',NULL,NULL,NULL,NULL,true,40),
 ('LOG_REGION','地区','SELECT','EVENT.business_region_code','REGION',NULL,NULL,NULL,true,50),
 ('LOG_REPORTER_PHONE','填报人联系方式','TEXT','EVENT.reporter_phone',NULL,NULL,NULL,NULL,true,70),
 ('LOG_SAMPLE_CONTACT','物流样本点联系方式','TEXT','EVENT.sample_contact',NULL,NULL,NULL,NULL,true,80),
 ('LOG_SAMPLE_LATITUDE','纬度','DECIMAL','EVENT.sample_latitude',NULL,'度',9,6,true,90),
 ('LOG_SAMPLE_LONGITUDE','经度','DECIMAL','EVENT.sample_longitude',NULL,'度',10,6,true,100),
 ('LOG_BOARD_PRICE','车板价','DECIMAL','FACT.BOARD_PRICE',NULL,'元/吨',18,4,true,150);

INSERT INTO platform.logistics_core_field_applicability(field_code,product_code,sort_order)
SELECT field.code,product.code,field.sort_order
FROM platform.logistics_core_field_definition field
CROSS JOIN platform.product product
WHERE field.code IN (
  'surveyYear','surveyMonth','fillingDate','LOG_SAMPLE_NAME','LOG_REGION','LOG_REPORTER',
  'LOG_REPORTER_PHONE','LOG_SAMPLE_CONTACT','LOG_SAMPLE_LATITUDE','LOG_SAMPLE_LONGITUDE',
  'LOG_TRANSPORT_MODE','LOG_DIRECTION','LOG_ROUTE_VOLUME','LOG_FREIGHT_RATE',
  'LOG_BOARD_PRICE','LOG_STATUS');

COMMENT ON COLUMN logistics.route_event.monitoring_period_code IS
    'Legacy versioned monitoring-period value. New public logistics surveys use survey_year/survey_month.';
COMMENT ON COLUMN logistics.route_event.origin_node_code IS
    'Legacy versioned route node. New public logistics surveys use business_region_code and direction_code.';
COMMENT ON COLUMN logistics.route_event.destination_node_code IS
    'Legacy versioned route node. New public logistics surveys use business_region_code and direction_code.';
COMMENT ON COLUMN logistics.route_event.business_region_code IS
    'Public logistics sample-point region; internal node identifiers are never exposed.';
COMMENT ON CONSTRAINT route_fact_fact_code_check ON logistics.route_fact IS
    'BOARD_PRICE is independent from FREIGHT_RATE; neither value includes or derives the other.';

UPDATE overview.indicator_definition
SET annual_comparison_enabled=true
WHERE code IN ('LOGISTICS_INFLOW_VOLUME','LOGISTICS_OUTFLOW_VOLUME');

ALTER TABLE overview.annual_comparison_metric_binding
    DROP CONSTRAINT annual_comparison_metric_binding_storage_code_check;
ALTER TABLE overview.annual_comparison_metric_binding
    ADD CONSTRAINT annual_comparison_metric_binding_storage_code_check
        CHECK (storage_code IN (
            'PRODUCTION_CORE','PRODUCTION_METADATA','PRODUCTION_QUALITY',
            'PRODUCTION_COST','PRODUCTION_INSURANCE','PRODUCTION_SUBSIDY',
            'MARKET_CORE','MARKET_FACT','LOGISTICS_FACT'
        ));
-- A single physical logistics fact is intentionally consumed by separate
-- direction-scoped indicators (inflow and outflow). metric_code remains the
-- authoritative one-binding-per-indicator key.
ALTER TABLE overview.annual_comparison_metric_binding
    DROP CONSTRAINT annual_comparison_metric_binding_storage_code_field_code_key;

INSERT INTO overview.indicator_definition(
    code,name,unit_code,source_domain,sort_order,aggregation_code,annual_comparison_enabled,
    formula,source_relation,calculation_version)
VALUES ('LOGISTICS_AVERAGE_FREIGHT_RATE','核定平均物流运价（不含车板价）','元/吨','LOGISTICS',170,
    'AVERAGE',true,'审核通过物流事件的物流运价算术平均值，不含且不读取车板价',
    '物流核定事件及独立运价明细','物流公共契约口径第2版');

INSERT INTO overview.annual_comparison_metric_binding(metric_code,storage_code,field_code) VALUES
    ('LOGISTICS_INFLOW_VOLUME','LOGISTICS_FACT','ROUTE_VOLUME'),
    ('LOGISTICS_OUTFLOW_VOLUME','LOGISTICS_FACT','ROUTE_VOLUME'),
    ('LOGISTICS_AVERAGE_FREIGHT_RATE','LOGISTICS_FACT','FREIGHT_RATE');

-- Supply provenance follows the explicit survey period and public business
-- region. Legacy rows were backfilled into the same governed columns by V88
-- and this migration, so old approved sources remain valid without exposing
-- their period or node identifiers.
CREATE OR REPLACE FUNCTION supply.validate_release_period_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE release_row supply.source_release%ROWTYPE;
BEGIN
    SELECT * INTO release_row FROM supply.source_release WHERE source_release_id=NEW.source_release_id;
    IF release_row.source_domain='PRODUCTION' AND NOT EXISTS(
        SELECT 1 FROM production.production_record record
        JOIN platform.supply_survey_period period ON period.code=release_row.period_code
        WHERE record.record_id=release_row.source_record_id AND record.version=release_row.source_version
          AND record.product_code=release_row.product_code AND record.region_code=release_row.region_code
          AND EXTRACT(YEAR FROM record.survey_date)=period.survey_year
          AND (period.survey_quarter IS NULL
            OR period.survey_quarter='Q'||EXTRACT(QUARTER FROM record.survey_date)::integer::text)) THEN
        RAISE EXCEPTION 'production source does not belong to the supply survey period';
    ELSIF release_row.source_domain='LOGISTICS' AND NOT EXISTS(
        SELECT 1 FROM logistics.route_event event
        JOIN platform.supply_survey_period period ON period.code=release_row.period_code
        WHERE event.event_id::text=release_row.source_record_id AND event.version=release_row.source_version
          AND event.product_code=release_row.product_code
          AND COALESCE(event.business_region_code,
            CASE event.direction_code WHEN 'INFLOW' THEN event.destination_region_code
              ELSE event.origin_region_code END)=release_row.region_code
          AND event.survey_period_governance_state='CONFIRMED'
          AND event.survey_year=period.survey_year
          AND (period.survey_quarter IS NULL OR (event.survey_month IS NOT NULL
            AND period.survey_quarter='Q'||EXTRACT(QUARTER FROM
              make_date(event.survey_year,event.survey_month,1))::integer::text))) THEN
        RAISE EXCEPTION 'logistics source does not belong to the supply survey period';
    ELSIF release_row.source_domain='MANUAL' AND NOT EXISTS(
        SELECT 1 FROM supply.manual_input_decision decision
        WHERE decision.manual_input_id=NEW.manual_input_id
          AND decision.period_code=release_row.period_code) THEN
        RAISE EXCEPTION 'manual source does not belong to the supply business period';
    END IF;
    RETURN NEW;
END $$;
