-- The active survey captures the external object's purchase and sale prices;
-- this group contains purchase-side operational quantities, not a platform
-- transaction direction or a derived transaction price.
UPDATE platform.page_column_group
SET label = '采购业务'
WHERE business_domain = 'MARKET'
  AND page_kind = 'MONITORING'
  AND code = 'PURCHASE';
