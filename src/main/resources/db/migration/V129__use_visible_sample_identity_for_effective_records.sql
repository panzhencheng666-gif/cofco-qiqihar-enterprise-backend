-- A physical survey sample is the person or business identified by the
-- business-directory name and contact. Product is a child observation and is
-- deliberately excluded from the sample identity.

CREATE OR REPLACE VIEW production.production_record_business_identity AS
WITH identity_fact AS (
    SELECT metadata.record_id,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_NAME') sample_name,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_CONTACT') sample_contact
    FROM production.production_record_submission_metadata metadata
    WHERE metadata.field_code IN ('PROD_SAMPLE_NAME','PROD_SAMPLE_CONTACT')
    GROUP BY metadata.record_id
), normalized AS (
    SELECT record.record_id,
           lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_name,'')),NFKC),
               '[[:space:]]+','','g')) sample_name,
           lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_contact,'')),NFKC),
               '[[:space:]()（）-]+','','g')) sample_contact,
           record.sample_point_id,
           record.region_code
    FROM production.production_record record
    LEFT JOIN identity_fact fact ON fact.record_id=record.record_id
)
SELECT normalized.record_id,
       CASE
         WHEN normalized.sample_name<>'' AND normalized.sample_contact<>''
           THEN 'VISIBLE|'||normalized.sample_name||'|'||normalized.sample_contact
         WHEN normalized.sample_name<>''
           THEN 'LEGACY_VISIBLE_NAME|'||normalized.region_code||'|'||normalized.sample_name
         WHEN normalized.sample_contact<>''
           THEN 'LEGACY_VISIBLE_CONTACT|'||normalized.region_code||'|'||normalized.sample_contact
         WHEN normalized.sample_point_id IS NOT NULL
           THEN 'POINT|'||normalized.sample_point_id::text
         ELSE 'RECORD|'||normalized.record_id
       END business_identity
FROM normalized;

CREATE OR REPLACE VIEW production.effective_approved_production_record AS
SELECT ranked.record_id,ranked.business_identity,ranked.survey_year
FROM (
    SELECT record.record_id,identity.business_identity,record.survey_year,
           row_number() OVER (
             PARTITION BY identity.business_identity,record.product_code,record.survey_year
             ORDER BY
               CASE WHEN record.survey_period_precision='YEAR' THEN 1 ELSE 0 END DESC,
               record.survey_date DESC,
               record.version DESC,
               record.record_id DESC
           ) effective_rank
    FROM production.production_record record
    JOIN production.production_record_business_identity identity
      ON identity.record_id=record.record_id
    WHERE record.status_code='APPROVED'
      AND record.survey_period_governance_state='CONFIRMED'
) ranked
WHERE ranked.effective_rank=1;

CREATE VIEW market.market_record_business_identity AS
WITH identity_fact AS (
    SELECT value.record_id,
           max(value.value) FILTER (
               WHERE value.field_code='MKT_SAMPLE_NAME') sample_name,
           max(value.value) FILTER (
               WHERE value.field_code='MKT_SAMPLE_CONTACT') sample_contact
    FROM market.market_record_core_value value
    WHERE value.field_code IN ('MKT_SAMPLE_NAME','MKT_SAMPLE_CONTACT')
    GROUP BY value.record_id
), normalized AS (
    SELECT record.record_id,
           lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_name,'')),NFKC),
               '[[:space:]]+','','g')) sample_name,
           lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_contact,'')),NFKC),
               '[[:space:]()（）-]+','','g')) sample_contact,
           record.sample_point_id,
           record.region_code
    FROM market.market_record record
    LEFT JOIN identity_fact fact ON fact.record_id=record.record_id
)
SELECT normalized.record_id,
       CASE
         WHEN normalized.sample_name<>'' AND normalized.sample_contact<>''
           THEN 'VISIBLE|'||normalized.sample_name||'|'||normalized.sample_contact
         WHEN normalized.sample_name<>''
           THEN 'LEGACY_VISIBLE_NAME|'||normalized.region_code||'|'||normalized.sample_name
         WHEN normalized.sample_contact<>''
           THEN 'LEGACY_VISIBLE_CONTACT|'||normalized.region_code||'|'||normalized.sample_contact
         WHEN normalized.sample_point_id IS NOT NULL
           THEN 'POINT|'||normalized.sample_point_id::text
         ELSE 'RECORD|'||normalized.record_id
       END business_identity
FROM normalized;

CREATE VIEW market.effective_approved_market_record AS
SELECT ranked.record_id,ranked.business_identity,ranked.survey_year,ranked.survey_month
FROM (
    SELECT record.record_id,identity.business_identity,
           record.survey_year,record.survey_month,
           row_number() OVER (
             PARTITION BY identity.business_identity,record.product_code,record.survey_year,
                          COALESCE(record.survey_month,0)
             ORDER BY record.trade_date DESC,record.version DESC,record.record_id DESC
           ) effective_rank
    FROM market.market_record record
    JOIN market.market_record_business_identity identity
      ON identity.record_id=record.record_id
    WHERE record.status_code='APPROVED'
      AND record.survey_period_governance_state='CONFIRMED'
) ranked
WHERE ranked.effective_rank=1;

COMMENT ON VIEW production.production_record_business_identity IS
    '产情公开业务样本身份：姓名和联系方式；产品是该样本的子记录，不参与样本数量计算。';
COMMENT ON VIEW production.effective_approved_production_record IS
    '按公开样本身份、产品和年度选取最新核定产情记录；原始核定台账完整保留。';
COMMENT ON VIEW market.market_record_business_identity IS
    '市场公开业务样本身份：名称和联系方式；产品是该样本的子记录，不参与样本数量计算。';
COMMENT ON VIEW market.effective_approved_market_record IS
    '按公开样本身份、产品和填报月份选取最新核定市场记录；原始核定台账完整保留。';

ALTER VIEW production.production_record_business_identity OWNER TO qiqihar_migration_owner;
ALTER VIEW production.effective_approved_production_record OWNER TO qiqihar_migration_owner;
ALTER VIEW market.market_record_business_identity OWNER TO qiqihar_migration_owner;
ALTER VIEW market.effective_approved_market_record OWNER TO qiqihar_migration_owner;
GRANT SELECT ON TABLE production.production_record_business_identity TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE production.effective_approved_production_record TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE market.market_record_business_identity TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE market.effective_approved_market_record TO qiqihar_enterprise_runtime;

-- Retire hidden governance inputs from the active business-directory contract.
-- Historical stored values and audit tables remain readable for traceability.
DELETE FROM platform.page_column_group_field
WHERE business_domain='PRODUCTION'
  AND field_code IN (
    'PROD_SAMPLE_SUBJECT_CODE','PROD_SURPLUS_SUBJECT_CODE','PROD_SURPLUS_CUTOFF_DATE');
DELETE FROM platform.page_definition_field
WHERE business_domain='PRODUCTION'
  AND field_code IN (
    'PROD_SAMPLE_SUBJECT_CODE','PROD_SURPLUS_SUBJECT_CODE','PROD_SURPLUS_CUTOFF_DATE');

DELETE FROM platform.page_column_group_field
WHERE business_domain='MARKET'
  AND field_code IN (
    'MKT_SAMPLE_SUBJECT_CODE','MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE',
    'MKT_STORAGE_REGION_CODE','MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE',
    'MKT_INVENTORY_POLICY_ATTRIBUTE');
DELETE FROM platform.page_definition_field
WHERE business_domain='MARKET'
  AND field_code IN (
    'MKT_SAMPLE_SUBJECT_CODE','MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE',
    'MKT_STORAGE_REGION_CODE','MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE',
    'MKT_INVENTORY_POLICY_ATTRIBUTE');
DELETE FROM platform.market_core_field_applicability
WHERE field_code IN (
    'MKT_SAMPLE_SUBJECT_CODE','MKT_INVENTORY_HOLDER_CODE','MKT_INVENTORY_OWNERSHIP_TYPE',
    'MKT_STORAGE_REGION_CODE','MKT_CARGO_OWNER_CODE','MKT_INVENTORY_CUTOFF_DATE',
    'MKT_INVENTORY_POLICY_ATTRIBUTE');

UPDATE overview.region_surplus_calculation_contract
SET name='地区余粮公开填报口径第1版',
    production_identity_source='PROD_SAMPLE_NAME + PROD_SAMPLE_CONTACT',
    production_cutoff_source='survey_year / survey_month',
    formula='按公开样本身份分别采用最新产情期末余粮和市场现有库存后合计'
WHERE status_code='ACTIVE';
