-- Region surplus is derived only from approved production surplus and enterprise
-- ending-inventory facts that carry an explicit, auditable ownership contract.

INSERT INTO platform.production_fact_definition
    (code,category,label,value_type,unit,description,decimal_precision,decimal_scale)
VALUES
    ('PROD_SURPLUS_SUBJECT_CODE','DETAIL','余粮主体唯一标识','TEXT',NULL,
     '同一农户或样本主体跨期稳定且不可复用的业务标识',18,4),
    ('PROD_SURPLUS_CUTOFF_DATE','DETAIL','余粮统计截止日','TEXT',NULL,
     '地区余粮采用的统一统计截止日，格式 YYYY-MM-DD',18,4)
ON CONFLICT (code) DO UPDATE
SET category=EXCLUDED.category,label=EXCLUDED.label,value_type=EXCLUDED.value_type,
    unit=EXCLUDED.unit,description=EXCLUDED.description,
    decimal_precision=EXCLUDED.decimal_precision,decimal_scale=EXCLUDED.decimal_scale;

INSERT INTO platform.production_fact_applicability
    (fact_code,product_code,object_type_code,business_domain,page_kind,sort_order)
SELECT field.code,product.code,object_type.code,'PRODUCTION','MONITORING',field.sort_order
FROM platform.product product
CROSS JOIN platform.object_type object_type
CROSS JOIN (VALUES
    ('PROD_SURPLUS_SUBJECT_CODE',1120),('PROD_SURPLUS_CUTOFF_DATE',1130)
) field(code,sort_order)
WHERE object_type.code IN ('FARMER','VILLAGE_COMMITTEE','AGRICULTURAL_TECH_STATION')
ON CONFLICT DO NOTHING;

INSERT INTO platform.field_definition(code,name,value_type)
VALUES
    ('PROD_SURPLUS_SUBJECT_CODE','产情余粮主体唯一标识','TEXT'),
    ('PROD_SURPLUS_CUTOFF_DATE','产情余粮统计截止日','TEXT')
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,value_type=EXCLUDED.value_type;

INSERT INTO platform.page_definition_field(product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'PRODUCTION','MONITORING',field.code,field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_SURPLUS_SUBJECT_CODE',87),('PROD_SURPLUS_CUTOFF_DATE',88)
) field(code,sort_order)
ON CONFLICT DO NOTHING;

INSERT INTO platform.page_column_group_field(
    product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'PRODUCTION','MONITORING','REPORT',field.code,field.sort_order,NULL,field.description
FROM platform.product product
CROSS JOIN (VALUES
    ('PROD_SURPLUS_SUBJECT_CODE',87,'填报期末余粮时必填，用于主体级最新审核版本去重'),
    ('PROD_SURPLUS_CUTOFF_DATE',88,'填报期末余粮时必填，跨来源截止日不一致则结果不可用')
) field(code,sort_order,description)
ON CONFLICT DO NOTHING;

ALTER TABLE platform.market_core_field_definition
    DROP CONSTRAINT market_core_field_definition_supported_metadata_check;
ALTER TABLE platform.market_core_field_definition
    ADD CONSTRAINT market_core_field_definition_supported_metadata_check CHECK (
        (domain_binding='OBJECT_TYPE' AND capability='OBJECT_TYPE_CONTEXT'
            AND control_type='SELECT' AND required)
        OR (domain_binding='REGION' AND capability='GENERIC'
            AND control_type='REGION_HIERARCHY' AND required)
        OR (domain_binding='TRADE_DATE' AND capability='GENERIC'
            AND control_type='DATE' AND required)
        OR (domain_binding='REPORTED_AT' AND capability='GENERIC'
            AND control_type='READONLY_DATETIME' AND NOT required)
        OR (domain_binding='PURCHASE_BASE_PRICE' AND capability='PURCHASE_BASE_PRICE'
            AND control_type='DECIMAL' AND required)
        OR (domain_binding='SALE_BASE_PRICE' AND capability='SALE_BASE_PRICE'
            AND control_type='DECIMAL' AND required)
        OR (domain_binding='CARRIAGE_BOARD_AMOUNT' AND capability='PRICE_COMPONENT'
            AND control_type='DECIMAL' AND required)
        OR (domain_binding='PACKAGING_FORM' AND capability='GENERIC'
            AND control_type='SELECT' AND required)
        OR (domain_binding='PACKAGING_AMOUNT' AND capability='PRICE_COMPONENT'
            AND control_type='DECIMAL' AND required)
        OR (domain_binding='FREIGHT_AMOUNT' AND capability='PRICE_COMPONENT'
            AND control_type='DECIMAL' AND required)
        OR (domain_binding='EXTENSION' AND capability='GENERIC'
            AND control_type IN ('TEXT','DECIMAL','SELECT','REGION_HIERARCHY','DATE'))
    );

INSERT INTO platform.market_core_field_definition
    (code,label,control_type,unit,decimal_precision,decimal_scale,sort_order,
     description,domain_binding,capability,required)
VALUES
    ('MKT_INVENTORY_HOLDER_CODE','库存填报主体唯一标识','TEXT',NULL,NULL,NULL,140,
     '实际持有或保管库存的企业稳定业务标识','EXTENSION','GENERIC',false),
    ('MKT_INVENTORY_OWNERSHIP_TYPE','库存权属','SELECT',NULL,NULL,NULL,141,
     '自有库存或代储库存；仅用于互斥采用，不拆分同一库存','EXTENSION','GENERIC',false),
    ('MKT_STORAGE_REGION_CODE','库存存放地区','REGION_HIERARCHY',NULL,NULL,NULL,142,
     '库存实物存放地行政区划代码','EXTENSION','GENERIC',false),
    ('MKT_CARGO_OWNER_CODE','货主唯一标识','TEXT',NULL,NULL,NULL,143,
     '库存实际货主的稳定业务标识，用于自有与代储去重','EXTENSION','GENERIC',false),
    ('MKT_INVENTORY_CUTOFF_DATE','库存统计截止日','DATE',NULL,NULL,NULL,144,
     '地区余粮采用的统一统计截止日','EXTENSION','GENERIC',false),
    ('MKT_INVENTORY_POLICY_ATTRIBUTE','库存政策属性','SELECT',NULL,NULL,NULL,145,
     '同一库存的分类标签，不参与重复加总','EXTENSION','GENERIC',false);

INSERT INTO platform.market_core_field_option(field_code,value,label,sort_order)
VALUES
    ('MKT_INVENTORY_OWNERSHIP_TYPE','OWNED','自有库存',10),
    ('MKT_INVENTORY_OWNERSHIP_TYPE','CUSTODIAL','代储库存',20),
    ('MKT_INVENTORY_POLICY_ATTRIBUTE','COMMERCIAL','商品库存',10),
    ('MKT_INVENTORY_POLICY_ATTRIBUTE','POLICY','政策性库存',20),
    ('MKT_INVENTORY_POLICY_ATTRIBUTE','POLICY_AND_COMMERCIAL','政策与商品复合属性',30);

INSERT INTO platform.field_definition(code,name,value_type)
VALUES
    ('MKT_INVENTORY_HOLDER_CODE','市场库存填报主体唯一标识','TEXT'),
    ('MKT_INVENTORY_OWNERSHIP_TYPE','市场库存权属','TEXT'),
    ('MKT_STORAGE_REGION_CODE','市场库存存放地区','TEXT'),
    ('MKT_CARGO_OWNER_CODE','市场库存货主唯一标识','TEXT'),
    ('MKT_INVENTORY_CUTOFF_DATE','市场库存统计截止日','DATE'),
    ('MKT_INVENTORY_POLICY_ATTRIBUTE','市场库存政策属性','TEXT')
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,value_type=EXCLUDED.value_type;

INSERT INTO platform.page_definition_field(product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'MARKET','MONITORING',field.code,field.sort_order
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_INVENTORY_HOLDER_CODE',140),('MKT_INVENTORY_OWNERSHIP_TYPE',141),
    ('MKT_STORAGE_REGION_CODE',142),('MKT_CARGO_OWNER_CODE',143),
    ('MKT_INVENTORY_CUTOFF_DATE',144),('MKT_INVENTORY_POLICY_ATTRIBUTE',145)
) field(code,sort_order);

INSERT INTO platform.page_column_group_field(
    product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'MARKET','MONITORING','MARKET',field.code,field.sort_order,NULL,field.description
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_INVENTORY_HOLDER_CODE',140,'填报期末库存时必填，用于企业主体级最新版本去重'),
    ('MKT_INVENTORY_OWNERSHIP_TYPE',141,'填报期末库存时必填，自有库存优先于同货主代储库存'),
    ('MKT_STORAGE_REGION_CODE',142,'填报期末库存时必填，地区余粮按实物存放地归集'),
    ('MKT_CARGO_OWNER_CODE',143,'填报期末库存时必填，用于货主级去重'),
    ('MKT_INVENTORY_CUTOFF_DATE',144,'填报期末库存时必填，跨来源截止日不一致则结果不可用'),
    ('MKT_INVENTORY_POLICY_ATTRIBUTE',145,'政策属性仅作分类标签，同一库存只采用一次')
) field(code,sort_order,description);

INSERT INTO platform.market_core_field_applicability(
    product_code,business_domain,page_kind,field_code,domain_binding)
SELECT product.code,'MARKET','MONITORING',field.code,'EXTENSION'
FROM platform.product product
CROSS JOIN (VALUES
    ('MKT_INVENTORY_HOLDER_CODE'),('MKT_INVENTORY_OWNERSHIP_TYPE'),
    ('MKT_STORAGE_REGION_CODE'),('MKT_CARGO_OWNER_CODE'),
    ('MKT_INVENTORY_CUTOFF_DATE'),('MKT_INVENTORY_POLICY_ATTRIBUTE')
) field(code);

INSERT INTO overview.indicator_definition(code,name,unit_code,source_domain,sort_order)
VALUES ('REGION_SURPLUS','地区余粮','吨','SUPPLY',90);

COMMENT ON COLUMN platform.market_core_field_definition.description IS
    'Form contract metadata. Region-surplus ownership fields are conditionally required when ENDING_INVENTORY is reported.';
