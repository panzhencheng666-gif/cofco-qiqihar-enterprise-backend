CREATE VIEW production.production_record_business_identity AS
WITH identity_fact AS (
    SELECT metadata.record_id,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_SUBJECT_CODE') sample_subject_code,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SURPLUS_SUBJECT_CODE') surplus_subject_code,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_NAME') sample_name,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_CONTACT') sample_contact,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_LATITUDE') sample_latitude,
           max(metadata.value) FILTER (
               WHERE metadata.field_code='PROD_SAMPLE_LONGITUDE') sample_longitude
    FROM production.production_record_submission_metadata metadata
    WHERE metadata.field_code IN (
        'PROD_SAMPLE_SUBJECT_CODE','PROD_SURPLUS_SUBJECT_CODE','PROD_SAMPLE_NAME',
        'PROD_SAMPLE_CONTACT','PROD_SAMPLE_LATITUDE','PROD_SAMPLE_LONGITUDE')
    GROUP BY metadata.record_id
), normalized AS (
    SELECT record.record_id,
           record.product_code,
           record.region_code,
           record.object_type_code,
           record.cultivar_code,
           NULLIF(lower(regexp_replace(normalize(btrim(
               COALESCE(fact.sample_subject_code,fact.surplus_subject_code)),NFKC),
               '\\s+','','g')),'') stable_subject_code,
           lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_name,'')),NFKC),
               '\\s+','','g')) sample_name,
           lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_contact,'')),NFKC),
               '\\s+','','g')) sample_contact,
           CASE
             WHEN btrim(COALESCE(fact.sample_latitude,'')) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
               THEN (btrim(fact.sample_latitude)::numeric)::text
             ELSE lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_latitude,'')),NFKC),
               '\\s+','','g'))
           END sample_latitude,
           CASE
             WHEN btrim(COALESCE(fact.sample_longitude,'')) ~ '^[+-]?[0-9]+([.][0-9]+)?$'
               THEN (btrim(fact.sample_longitude)::numeric)::text
             ELSE lower(regexp_replace(normalize(btrim(COALESCE(fact.sample_longitude,'')),NFKC),
               '\\s+','','g'))
           END sample_longitude,
           record.sample_point_id::text sample_point_id
    FROM production.production_record record
    LEFT JOIN identity_fact fact ON fact.record_id=record.record_id
)
SELECT normalized.record_id,
       normalized.product_code||'|'||normalized.region_code||'|'||
       normalized.object_type_code||'|'||COALESCE(normalized.cultivar_code,'*')||'|'||
       CASE
         WHEN normalized.stable_subject_code IS NOT NULL
           THEN 'SUBJECT|'||normalized.stable_subject_code
         WHEN normalized.sample_name<>'' OR normalized.sample_contact<>''
           OR normalized.sample_latitude<>'' OR normalized.sample_longitude<>''
           THEN 'FINGERPRINT|'||normalized.sample_name||'|'||normalized.sample_contact||'|'||
             normalized.sample_latitude||'|'||normalized.sample_longitude
         ELSE 'FALLBACK|'||COALESCE(normalized.sample_point_id,normalized.record_id)
       END business_identity
FROM normalized;

CREATE VIEW production.effective_approved_production_record AS
SELECT ranked.record_id,ranked.business_identity,ranked.survey_year
FROM (
    SELECT record.record_id,identity.business_identity,record.survey_year,
           row_number() OVER (
             PARTITION BY identity.business_identity,record.survey_year
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

COMMENT ON VIEW production.production_record_business_identity IS
    '统一的产情业务样本身份契约；优先显式主体编码，其次样本名称、联系方式和坐标，最后回退样本点或记录。';
COMMENT ON VIEW production.effective_approved_production_record IS
    '年度指标消费者统一使用的最新核定产情记录；原始核定台账仍完整保留。';

ALTER VIEW production.production_record_business_identity OWNER TO qiqihar_migration_owner;
ALTER VIEW production.effective_approved_production_record OWNER TO qiqihar_migration_owner;
GRANT SELECT ON TABLE production.production_record_business_identity TO qiqihar_enterprise_runtime;
GRANT SELECT ON TABLE production.effective_approved_production_record TO qiqihar_enterprise_runtime;
