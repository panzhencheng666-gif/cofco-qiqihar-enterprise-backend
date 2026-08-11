INSERT INTO platform.production_fact_definition
    (code,category,label,value_type,unit,description,decimal_precision,decimal_scale)
VALUES ('PROD_SAMPLE_SUBJECT_CODE','DETAIL','样本主体唯一标识','TEXT',NULL,
        '同一产情主体跨产品、跨记录稳定且不可复用的业务标识',18,4);

INSERT INTO platform.production_fact_applicability
    (fact_code,product_code,object_type_code,business_domain,page_kind,sort_order)
SELECT 'PROD_SAMPLE_SUBJECT_CODE',product.code,object_type.code,'PRODUCTION','MONITORING',1005
FROM platform.product product
CROSS JOIN platform.object_type object_type
WHERE object_type.business_domain='PRODUCTION';

INSERT INTO platform.field_definition(code,name,value_type)
VALUES ('PROD_SAMPLE_SUBJECT_CODE','产情样本主体唯一标识','TEXT');

INSERT INTO platform.page_definition_field(
    product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'PRODUCTION','MONITORING','PROD_SAMPLE_SUBJECT_CODE',22
FROM platform.product product;

INSERT INTO platform.page_column_group_field(
    product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'PRODUCTION','MONITORING','REPORT','PROD_SAMPLE_SUBJECT_CODE',22,NULL,
       '用于正式样本点主体唯一性；缺失时审核来源进入纠错清单，不按名称合并'
FROM platform.product product;

INSERT INTO platform.market_core_field_definition
    (code,label,control_type,unit,decimal_precision,decimal_scale,sort_order,
     description,domain_binding,capability,required)
VALUES ('MKT_SAMPLE_SUBJECT_CODE','样本主体唯一标识','TEXT',NULL,NULL,NULL,127,
        '同一市场主体跨产品、跨记录稳定且不可复用的业务标识',
        'EXTENSION','GENERIC',false);

INSERT INTO platform.field_definition(code,name,value_type)
VALUES ('MKT_SAMPLE_SUBJECT_CODE','市场样本主体唯一标识','TEXT');

INSERT INTO platform.page_definition_field(
    product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'MARKET','MONITORING','MKT_SAMPLE_SUBJECT_CODE',127
FROM platform.product product;

INSERT INTO platform.page_column_group_field(
    product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'MARKET','MONITORING','MARKET','MKT_SAMPLE_SUBJECT_CODE',127,NULL,
       '用于正式样本点主体唯一性；缺失时审核来源进入纠错清单，不按名称合并'
FROM platform.product product;

INSERT INTO platform.market_core_field_applicability(
    product_code,business_domain,page_kind,field_code,domain_binding)
SELECT product.code,'MARKET','MONITORING','MKT_SAMPLE_SUBJECT_CODE','EXTENSION'
FROM platform.product product;
