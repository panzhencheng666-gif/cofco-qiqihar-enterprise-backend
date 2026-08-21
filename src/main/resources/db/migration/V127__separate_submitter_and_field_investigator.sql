-- The authenticated account is the reporter. The person who performed the
-- field investigation and that person's phone are separate business facts.

INSERT INTO platform.field_definition(code,name,value_type) VALUES
    ('PROD_SURVEYOR_NAME','产情调研人','TEXT'),
    ('PROD_SURVEYOR_PHONE','产情调研人联系方式','TEXT'),
    ('MKT_SURVEYOR_NAME','市场调研人','TEXT'),
    ('MKT_SURVEYOR_PHONE','市场调研人联系方式','TEXT')
ON CONFLICT (code) DO UPDATE
SET name=EXCLUDED.name,value_type=EXCLUDED.value_type;

-- Free the legacy phone position first, then shift the following public fields
-- through a collision-free temporary range before mounting the two surveyor facts.
DELETE FROM platform.page_column_group_field
WHERE business_domain='PRODUCTION' AND page_kind='MONITORING'
  AND field_code='PROD_REPORTER_PHONE';
DELETE FROM platform.page_definition_field
WHERE business_domain='PRODUCTION' AND page_kind='MONITORING'
  AND field_code='PROD_REPORTER_PHONE';
UPDATE platform.page_definition_field
SET sort_order=sort_order+1000
WHERE business_domain='PRODUCTION' AND page_kind='MONITORING'
  AND sort_order>=94;
UPDATE platform.page_column_group_field
SET sort_order=sort_order+1000
WHERE business_domain='PRODUCTION' AND page_kind='MONITORING'
  AND sort_order>=94;
UPDATE platform.page_definition_field
SET sort_order=sort_order-999
WHERE business_domain='PRODUCTION' AND page_kind='MONITORING'
  AND sort_order>=1094;
UPDATE platform.page_column_group_field
SET sort_order=sort_order-999
WHERE business_domain='PRODUCTION' AND page_kind='MONITORING'
  AND sort_order>=1094;

INSERT INTO platform.page_definition_field(
    product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'PRODUCTION','MONITORING',field.code,field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_SURVEYOR_NAME',93),('PROD_SURVEYOR_PHONE',94)
) field(code,sort_order)
ON CONFLICT (product_code,business_domain,page_kind,field_code) DO UPDATE
SET sort_order=EXCLUDED.sort_order;

INSERT INTO platform.page_column_group_field(
    product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'PRODUCTION','MONITORING','REPORT',field.code,field.sort_order,NULL,
       '调研人是实际开展现场调查的人员；与系统自动记录的填报账号分离'
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_SURVEYOR_NAME',93),('PROD_SURVEYOR_PHONE',94)
) field(code,sort_order)
ON CONFLICT (product_code,business_domain,page_kind,group_code,field_code) DO UPDATE
SET sort_order=EXCLUDED.sort_order,description=EXCLUDED.description;

-- Existing phone values were entered as field-investigator contact values.
INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
SELECT metadata.record_id,'PROD_SURVEYOR_PHONE',metadata.value
FROM production.production_record_submission_metadata metadata
WHERE metadata.field_code='PROD_REPORTER_PHONE'
ON CONFLICT (record_id,field_code) DO NOTHING;

DELETE FROM production.production_record_submission_metadata legacy
WHERE legacy.field_code='PROD_REPORTER_PHONE'
  AND EXISTS (
      SELECT 1
      FROM production.production_record_submission_metadata migrated
      WHERE migrated.record_id=legacy.record_id
        AND migrated.field_code='PROD_SURVEYOR_PHONE'
        AND migrated.value=legacy.value
  );

-- Some imported corn files put investigator names in the reported-cultivar
-- column. Preserve actual product/cultivar labels and migrate only values that
-- cannot be matched unambiguously to product master data.
WITH misclassified AS (
    SELECT metadata.record_id,metadata.value
    FROM production.production_record_submission_metadata metadata
    JOIN production.production_record record ON record.record_id=metadata.record_id
    WHERE metadata.field_code='PROD_CULTIVAR_NAME'
      AND record.product_code='CORN'
      AND NOT EXISTS (
          SELECT 1 FROM platform.product product
          WHERE product.code=record.product_code
            AND lower(btrim(metadata.value)) IN (
                lower(btrim(product.code)),lower(btrim(product.name)))
      )
      AND NOT EXISTS (
          SELECT 1 FROM platform.cultivar cultivar
          WHERE cultivar.product_code=record.product_code
            AND lower(btrim(metadata.value)) IN (
                lower(btrim(cultivar.code)),lower(btrim(cultivar.name)))
      )
)
INSERT INTO production.production_record_submission_metadata(record_id,field_code,value)
SELECT record_id,'PROD_SURVEYOR_NAME',value
FROM misclassified
ON CONFLICT (record_id,field_code) DO NOTHING;

WITH misclassified AS (
    SELECT metadata.record_id,metadata.value
    FROM production.production_record_submission_metadata metadata
    JOIN production.production_record record ON record.record_id=metadata.record_id
    WHERE metadata.field_code='PROD_CULTIVAR_NAME'
      AND record.product_code='CORN'
      AND NOT EXISTS (
          SELECT 1 FROM platform.product product
          WHERE product.code=record.product_code
            AND lower(btrim(metadata.value)) IN (
                lower(btrim(product.code)),lower(btrim(product.name)))
      )
      AND NOT EXISTS (
          SELECT 1 FROM platform.cultivar cultivar
          WHERE cultivar.product_code=record.product_code
            AND lower(btrim(metadata.value)) IN (
                lower(btrim(cultivar.code)),lower(btrim(cultivar.name)))
      )
)
DELETE FROM production.production_record_submission_metadata cultivar
USING misclassified
WHERE cultivar.record_id=misclassified.record_id
  AND cultivar.field_code='PROD_CULTIVAR_NAME'
  AND EXISTS (
      SELECT 1
      FROM production.production_record_submission_metadata migrated
      WHERE migrated.record_id=misclassified.record_id
        AND migrated.field_code='PROD_SURVEYOR_NAME'
        AND migrated.value=misclassified.value
  );

UPDATE platform.field_definition
SET name='历史产情填报人联系方式（停用）'
WHERE code='PROD_REPORTER_PHONE';

-- Market monitoring uses the same reporter/surveyor separation.
DELETE FROM platform.page_column_group_field
WHERE business_domain='MARKET' AND page_kind='MONITORING'
  AND field_code='MKT_REPORTER_PHONE';
DELETE FROM platform.page_definition_field
WHERE business_domain='MARKET' AND page_kind='MONITORING'
  AND field_code='MKT_REPORTER_PHONE';
UPDATE platform.page_definition_field
SET sort_order=sort_order+1000
WHERE business_domain='MARKET' AND page_kind='MONITORING'
  AND sort_order>=122;
UPDATE platform.page_column_group_field
SET sort_order=sort_order+1000
WHERE business_domain='MARKET' AND page_kind='MONITORING'
  AND sort_order>=122;
UPDATE platform.page_definition_field
SET sort_order=sort_order-999
WHERE business_domain='MARKET' AND page_kind='MONITORING'
  AND sort_order>=1122;
UPDATE platform.page_column_group_field
SET sort_order=sort_order-999
WHERE business_domain='MARKET' AND page_kind='MONITORING'
  AND sort_order>=1122;

UPDATE platform.market_core_field_definition
SET sort_order=sort_order+1000
WHERE sort_order>=122;
UPDATE platform.market_core_field_definition
SET sort_order=sort_order-999
WHERE sort_order>=1122;
UPDATE platform.market_core_field_definition
SET label='历史填报人联系方式（停用）',required=false,sort_order=1121
WHERE code='MKT_REPORTER_PHONE';

INSERT INTO platform.market_core_field_definition(
    code,label,control_type,unit,decimal_precision,decimal_scale,sort_order,
    description,domain_binding,capability,required)
VALUES
    ('MKT_SURVEYOR_NAME','调研人','TEXT',NULL,NULL,NULL,121,
     '实际开展现场调查的人员','EXTENSION','GENERIC',false),
    ('MKT_SURVEYOR_PHONE','调研人联系方式','TEXT',NULL,NULL,NULL,122,
     '实际开展现场调查人员的联系方式','EXTENSION','GENERIC',false)
ON CONFLICT (code) DO UPDATE SET
    label=EXCLUDED.label,control_type=EXCLUDED.control_type,
    sort_order=EXCLUDED.sort_order,description=EXCLUDED.description,
    domain_binding=EXCLUDED.domain_binding,capability=EXCLUDED.capability,
    required=EXCLUDED.required;

INSERT INTO platform.page_definition_field(
    product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'MARKET','MONITORING',field.code,field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_SURVEYOR_NAME',121),('MKT_SURVEYOR_PHONE',122)
) field(code,sort_order)
ON CONFLICT (product_code,business_domain,page_kind,field_code) DO UPDATE
SET sort_order=EXCLUDED.sort_order;

INSERT INTO platform.page_column_group_field(
    product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'MARKET','MONITORING','MARKET',field.code,field.sort_order,NULL,
       '调研人是实际开展现场调查的人员；与系统自动记录的填报账号分离'
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_SURVEYOR_NAME',121),('MKT_SURVEYOR_PHONE',122)
) field(code,sort_order)
ON CONFLICT (product_code,business_domain,page_kind,group_code,field_code) DO UPDATE
SET sort_order=EXCLUDED.sort_order,description=EXCLUDED.description;

INSERT INTO platform.market_core_field_applicability(
    product_code,business_domain,page_kind,field_code,domain_binding)
SELECT product.code,'MARKET','MONITORING',field.code,'EXTENSION'
FROM platform.product product
CROSS JOIN (VALUES ('MKT_SURVEYOR_NAME'),('MKT_SURVEYOR_PHONE')) field(code)
ON CONFLICT (product_code,field_code) DO UPDATE
SET domain_binding=EXCLUDED.domain_binding;

INSERT INTO market.market_record_core_value(
    record_id,field_code,value,product_code,domain_binding)
SELECT value.record_id,'MKT_SURVEYOR_PHONE',value.value,value.product_code,'EXTENSION'
FROM market.market_record_core_value value
WHERE value.field_code='MKT_REPORTER_PHONE'
ON CONFLICT (record_id,field_code) DO NOTHING;

DELETE FROM market.market_record_core_value legacy
WHERE legacy.field_code='MKT_REPORTER_PHONE'
  AND EXISTS (
      SELECT 1
      FROM market.market_record_core_value migrated
      WHERE migrated.record_id=legacy.record_id
        AND migrated.field_code='MKT_SURVEYOR_PHONE'
        AND migrated.value=legacy.value
  );
DELETE FROM platform.market_core_field_applicability
WHERE field_code='MKT_REPORTER_PHONE';
UPDATE platform.field_definition
SET name='历史市场填报人联系方式（停用）'
WHERE code='MKT_REPORTER_PHONE';

-- Logistics stores the investigator fields as governed extensions. The old
-- event column remains only for migration compatibility and is not public.
DELETE FROM platform.logistics_core_field_applicability
WHERE field_code='LOG_REPORTER_PHONE';
UPDATE platform.logistics_core_field_definition
SET label='历史填报人联系方式（停用）',required=false,sort_order=1070
WHERE code='LOG_REPORTER_PHONE';

INSERT INTO platform.logistics_core_field_definition(
    code,label,control_type,binding,option_source,unit,
    decimal_precision,decimal_scale,required,sort_order)
VALUES
    ('LOG_SURVEYOR_NAME','调研人','TEXT','EXTENSION.LOG_SURVEYOR_NAME',NULL,NULL,NULL,NULL,false,70),
    ('LOG_SURVEYOR_PHONE','调研人联系方式','TEXT','EXTENSION.LOG_SURVEYOR_PHONE',NULL,NULL,NULL,NULL,false,75)
ON CONFLICT (code) DO UPDATE SET
    label=EXCLUDED.label,control_type=EXCLUDED.control_type,binding=EXCLUDED.binding,
    required=EXCLUDED.required,sort_order=EXCLUDED.sort_order;

INSERT INTO platform.logistics_core_field_applicability(field_code,product_code,sort_order)
SELECT field.code,product.code,field.sort_order
FROM platform.logistics_core_field_definition field
CROSS JOIN platform.product product
WHERE field.code IN ('LOG_SURVEYOR_NAME','LOG_SURVEYOR_PHONE')
ON CONFLICT (field_code,product_code) DO UPDATE
SET sort_order=EXCLUDED.sort_order;

INSERT INTO logistics.route_event_core_value(event_id,field_code,value)
SELECT event_id,'LOG_SURVEYOR_PHONE',reporter_phone
FROM logistics.route_event
WHERE reporter_phone IS NOT NULL AND btrim(reporter_phone)<>''
ON CONFLICT (event_id,field_code) DO NOTHING;

COMMENT ON TABLE production.production_record_submission_metadata IS
    'Production reporter identity is account-owned; field investigator and sample metadata are stored separately.';
