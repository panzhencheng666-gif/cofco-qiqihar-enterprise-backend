UPDATE platform.field_definition
SET name = '业务类型'
WHERE code = 'WORK_BUSINESS_DOMAIN'
  AND name = '业务域';

UPDATE platform.page_filter_definition
SET label = '业务类型',
    placeholder = '全部业务类型'
WHERE business_domain = 'WORKFLOW'
  AND page_kind = 'WORK_ITEMS'
  AND code = 'domain';

UPDATE platform.page_action
SET label = '确认数据来源'
WHERE business_domain = 'SUPPLY'
  AND page_kind = 'ACCOUNT'
  AND code = 'ADJUST';
