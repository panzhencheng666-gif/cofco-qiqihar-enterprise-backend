-- Specific cultivar is no longer part of the production or market collection
-- contract. Historical values remain stored for traceability, but the shared
-- business-page catalogue must not expose the field in forms or lists.

DELETE FROM platform.page_column_group_field
WHERE business_domain='PRODUCTION'
  AND page_kind='MONITORING'
  AND field_code='PROD_CULTIVAR';

DELETE FROM platform.page_column_group_field
WHERE business_domain='MARKET'
  AND page_kind='MONITORING'
  AND field_code='MKT_CULTIVAR_NAME';

COMMENT ON COLUMN platform.page_definition_field.field_code IS
    '业务字段挂载键；已退役采集字段可为历史数据完整性约束保留，但不得挂入页面列分组。';

COMMENT ON COLUMN production.production_record.cultivar_code IS
    '历史兼容字段；不再由产情表单、列表或批量模板采集。';
