UPDATE platform.design_sample_contract SET active=false WHERE active;

INSERT INTO platform.design_sample_contract(
    contract_version,contract_digest,active,activated_at)
VALUES(
    'design-sample-fields-v2',
    'sha256:0000000000000000000000000000000000000000000000000000000000000000',
    true,CURRENT_TIMESTAMP);

INSERT INTO platform.design_sample_domain_definition
SELECT 'design-sample-fields-v2',code,name,description,aliases,sort_order
FROM platform.design_sample_domain_definition
WHERE contract_version='design-sample-fields-v1';
INSERT INTO platform.design_sample_domain_definition
VALUES('design-sample-fields-v2','REFERENCE','设计参考','设计参考点内部分类','[]',30);

INSERT INTO platform.design_sample_product_definition
SELECT 'design-sample-fields-v2',code,name,aliases,sort_order
FROM platform.design_sample_product_definition
WHERE contract_version='design-sample-fields-v1';
INSERT INTO platform.design_sample_product_definition
VALUES('design-sample-fields-v2','GENERAL','通用','[]',40);

INSERT INTO platform.design_sample_object_type_definition
SELECT 'design-sample-fields-v2',domain_code,code,name,aliases,sort_order
FROM platform.design_sample_object_type_definition
WHERE contract_version='design-sample-fields-v1';
INSERT INTO platform.design_sample_object_type_definition
VALUES('design-sample-fields-v2','REFERENCE','REFERENCE_POINT','设计参考点','[]',10);

INSERT INTO platform.design_sample_context
SELECT 'design-sample-fields-v2',domain_code,product_code,object_type_code,sort_order
FROM platform.design_sample_context
WHERE contract_version='design-sample-fields-v1';
INSERT INTO platform.design_sample_context
VALUES('design-sample-fields-v2','REFERENCE','GENERAL','REFERENCE_POINT',290);

INSERT INTO platform.design_sample_field_definition
SELECT 'design-sample-fields-v2',code,section_code,label,description,value_type,
       numeric_precision,numeric_scale,max_length,unit,enum_options,required,
       nullable,default_value,editable,minimum_value,maximum_value,analysis_role
FROM platform.design_sample_field_definition
WHERE contract_version='design-sample-fields-v1';
INSERT INTO platform.design_sample_field_definition(
    contract_version,code,section_code,label,description,value_type,
    numeric_precision,numeric_scale,max_length,unit,enum_options,
    required,nullable,default_value,editable,minimum_value,maximum_value,analysis_role)
VALUES(
    'design-sample-fields-v2','DSP_ADDRESS','IDENTITY','详细地址',
    '填报人手工填写的设计参考点详细地址','STRING',
    NULL,NULL,500,NULL,'[]',false,true,NULL,true,NULL,NULL,'NONE');

INSERT INTO platform.design_sample_field_applicability
SELECT 'design-sample-fields-v2',domain_code,product_code,object_type_code,
       field_code,group_code,sort_order
FROM platform.design_sample_field_applicability
WHERE contract_version='design-sample-fields-v1';
INSERT INTO platform.design_sample_field_applicability
SELECT 'design-sample-fields-v2',domain_code,product_code,object_type_code,
       'DSP_ADDRESS','IDENTITY',65
FROM platform.design_sample_context
WHERE contract_version='design-sample-fields-v2';

INSERT INTO platform.design_sample_field_applicability(
    contract_version,domain_code,product_code,object_type_code,
    field_code,group_code,sort_order)
SELECT 'design-sample-fields-v2','REFERENCE','GENERAL','REFERENCE_POINT',
       field_code,'IDENTITY',sort_order
FROM (VALUES
    ('DSP_NAME',50),('DSP_REGION_CODE',60),
    ('DSP_LONGITUDE',70),('DSP_LATITUDE',80)
) fields(field_code,sort_order);

INSERT INTO platform.design_sample_alias_definition
SELECT 'design-sample-fields-v2',alias_type,domain_code,alias_code,canonical_code
FROM platform.design_sample_alias_definition
WHERE contract_version='design-sample-fields-v1';

UPDATE platform.design_sample_point
SET contract_version='design-sample-fields-v2';

UPDATE platform.design_sample_contract
SET contract_digest=platform.current_design_sample_contract_digest()
WHERE contract_version='design-sample-fields-v2';
