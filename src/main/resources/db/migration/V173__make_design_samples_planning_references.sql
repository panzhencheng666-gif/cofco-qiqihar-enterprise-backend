UPDATE platform.design_sample_contract SET active=false WHERE active;

INSERT INTO platform.design_sample_contract(
    contract_version,contract_digest,active,activated_at)
VALUES('design-sample-fields-v3',
    'sha256:0000000000000000000000000000000000000000000000000000000000000000',
    true,CURRENT_TIMESTAMP);

INSERT INTO platform.design_sample_domain_definition
SELECT 'design-sample-fields-v3',code,name,description,aliases,sort_order
FROM platform.design_sample_domain_definition WHERE contract_version='design-sample-fields-v2';
INSERT INTO platform.design_sample_product_definition
SELECT 'design-sample-fields-v3',code,name,aliases,sort_order
FROM platform.design_sample_product_definition WHERE contract_version='design-sample-fields-v2';
INSERT INTO platform.design_sample_object_type_definition
SELECT 'design-sample-fields-v3',domain_code,code,name,aliases,sort_order
FROM platform.design_sample_object_type_definition WHERE contract_version='design-sample-fields-v2';
INSERT INTO platform.design_sample_context
SELECT 'design-sample-fields-v3',domain_code,product_code,object_type_code,sort_order
FROM platform.design_sample_context WHERE contract_version='design-sample-fields-v2';

INSERT INTO platform.design_sample_field_definition
SELECT 'design-sample-fields-v3',code,section_code,label,description,value_type,
       numeric_precision,numeric_scale,max_length,unit,enum_options,required,
       nullable,default_value,editable,minimum_value,maximum_value,'NONE'
FROM platform.design_sample_field_definition
WHERE contract_version='design-sample-fields-v2'
  AND code IN ('DOMAIN_CODE','PRODUCT_CODE','OBJECT_TYPE_CODE','DSP_NAME',
    'DSP_REGION_CODE','DSP_ADDRESS','DSP_LONGITUDE','DSP_LATITUDE');

INSERT INTO platform.design_sample_field_definition(
    contract_version,code,section_code,label,description,value_type,
    numeric_precision,numeric_scale,max_length,unit,enum_options,
    required,nullable,default_value,editable,minimum_value,maximum_value,analysis_role)
VALUES
 ('design-sample-fields-v3','DSP_MAINTAINER_NAME','IDENTITY','维护人',
  '设计参考点维护联系人','STRING',NULL,NULL,100,NULL,'[]',false,true,NULL,true,NULL,NULL,'NONE'),
 ('design-sample-fields-v3','DSP_MAINTAINER_UNIT','IDENTITY','维护单位',
  '设计参考点维护单位','STRING',NULL,NULL,200,NULL,'[]',false,true,NULL,true,NULL,NULL,'NONE');

INSERT INTO platform.design_sample_field_applicability(
    contract_version,domain_code,product_code,object_type_code,field_code,group_code,sort_order)
SELECT 'design-sample-fields-v3',context.domain_code,context.product_code,context.object_type_code,
       field.field_code,'IDENTITY',field.sort_order
FROM platform.design_sample_context context
CROSS JOIN (VALUES
 ('DOMAIN_CODE',10),('PRODUCT_CODE',20),('OBJECT_TYPE_CODE',30),
 ('DSP_NAME',40),('DSP_REGION_CODE',50),('DSP_ADDRESS',60),
 ('DSP_LONGITUDE',70),('DSP_LATITUDE',80),
 ('DSP_MAINTAINER_NAME',90),('DSP_MAINTAINER_UNIT',100)
) field(field_code,sort_order)
WHERE context.contract_version='design-sample-fields-v3';

INSERT INTO platform.design_sample_alias_definition
SELECT 'design-sample-fields-v3',alias_type,domain_code,alias_code,canonical_code
FROM platform.design_sample_alias_definition WHERE contract_version='design-sample-fields-v2';

UPDATE platform.design_sample_point
SET contract_version='design-sample-fields-v3',
    values_json=(SELECT COALESCE(jsonb_object_agg(entry.key,entry.value),'{}'::jsonb)
                 FROM jsonb_each(values_json) entry
                 WHERE entry.key IN ('DSP_NAME','DSP_REGION_CODE','DSP_ADDRESS',
                   'DSP_LONGITUDE','DSP_LATITUDE','DSP_MAINTAINER_NAME','DSP_MAINTAINER_UNIT'));

UPDATE platform.design_sample_contract
SET contract_digest=platform.current_design_sample_contract_digest()
WHERE contract_version='design-sample-fields-v3';
