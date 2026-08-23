\set ON_ERROR_STOP on

BEGIN;
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

DO $$
BEGIN
    IF current_database() <> 'qiqihar_enterprise_dev' THEN
        RAISE EXCEPTION 'local reset requires exact database qiqihar_enterprise_dev';
    END IF;
    IF inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet THEN
        RAISE EXCEPTION 'local reset requires exact numeric loopback server 127.0.0.1';
    END IF;
END;
$$;

CREATE TEMP TABLE reset_control AS
SELECT :'expected_digest'::text AS expected_digest;

\if :apply
LOCK TABLE production.production_record, market.market_record,
    platform.business_import_draft, platform.import_row_result,
    workflow.work_item, evidence.evidence_photo,
    reporting.approved_dataset, reporting.report_preview,
    reporting.report_export_task, reporting.report_publication,
    supply.source_release, supply.source_adoption_set,
    supply.calculation_run, supply.result_version
IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF to_regclass('registry.sample_network_year') IS NOT NULL THEN
        EXECUTE 'LOCK TABLE registry.sample_network_year,registry.sample_network_membership '
             || 'IN SHARE ROW EXCLUSIVE MODE';
    END IF;
END;
$$;
\endif

CREATE TEMP TABLE protected_count_before(
    code text PRIMARY KEY,
    value bigint NOT NULL
);
INSERT INTO protected_count_before(code,value) VALUES
    ('production_other',(
      SELECT count(*) FROM production.production_record
      WHERE NOT (survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN')))),
    ('market_other',(
      SELECT count(*) FROM market.market_record
      WHERE NOT (survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN')))),
    ('draft_other',(
      SELECT count(*) FROM platform.business_import_draft
      WHERE NOT (domain_code IN ('PRODUCTION','MARKET')
        AND product_code IN ('CORN','RICE','SOYBEAN')
        AND (survey_period LIKE '2026%'
          OR canonical_record_id IN (
            SELECT record_id FROM production.production_record
            WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN')
            UNION ALL
            SELECT record_id FROM market.market_record
            WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN')))))),
    ('village',(
      SELECT count(*) FROM platform.region WHERE administrative_level='VILLAGE')),
    ('village_location',(
      SELECT count(*) FROM platform.region village
      JOIN platform.region_location location ON location.region_code=village.code
      WHERE village.administrative_level='VILLAGE')),
    ('sample_point',(SELECT count(*) FROM registry.sample_point)),
    ('import_job',(SELECT count(*) FROM platform.import_job)),
    ('security_user',(SELECT count(*) FROM platform.security_user)),
    ('business_audit',(SELECT count(*) FROM platform.business_audit_event)),
    ('report_audit',(SELECT count(*) FROM reporting.report_audit_event));

CREATE TEMP TABLE target_production AS
SELECT record_id,sample_point_id
FROM production.production_record
WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN');
ALTER TABLE target_production ADD PRIMARY KEY(record_id);

CREATE TEMP TABLE target_market AS
SELECT record_id,sample_point_id
FROM market.market_record
WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN');
ALTER TABLE target_market ADD PRIMARY KEY(record_id);

CREATE TEMP TABLE target_draft AS
SELECT import_draft_id
FROM platform.business_import_draft
WHERE domain_code IN ('PRODUCTION','MARKET')
  AND product_code IN ('CORN','RICE','SOYBEAN')
  AND (
    survey_period LIKE '2026%'
    OR canonical_record_id IN (
      SELECT record_id FROM target_production
      UNION ALL SELECT record_id FROM target_market
    )
  );
ALTER TABLE target_draft ADD PRIMARY KEY(import_draft_id);

CREATE TEMP TABLE target_import_row AS
SELECT row.import_job_id,row.row_number
FROM platform.import_row_result row
WHERE row.business_record_id IN (
        SELECT record_id FROM target_production
        UNION ALL SELECT record_id FROM target_market)
   OR row.import_draft_id IN (SELECT import_draft_id FROM target_draft);
ALTER TABLE target_import_row ADD PRIMARY KEY(import_job_id,row_number);

CREATE TEMP TABLE target_work_item AS
SELECT work_item_id
FROM workflow.work_item
WHERE source_id IN (
        SELECT record_id FROM target_production
        UNION ALL SELECT record_id FROM target_market
        UNION ALL SELECT import_draft_id::text FROM target_draft);
ALTER TABLE target_work_item ADD PRIMARY KEY(work_item_id);

CREATE TEMP TABLE target_photo AS
SELECT photo_id FROM evidence.evidence_photo photo
WHERE (photo.attached_domain='PRODUCTION'
       AND photo.attached_record_id IN (SELECT record_id FROM target_production))
   OR (photo.attached_domain='MARKET'
       AND photo.attached_record_id IN (SELECT record_id FROM target_market))
UNION
SELECT evidence.photo_id
FROM platform.business_import_draft_evidence evidence
WHERE evidence.import_draft_id IN (SELECT import_draft_id FROM target_draft);
ALTER TABLE target_photo ADD PRIMARY KEY(photo_id);

CREATE TEMP TABLE target_dataset AS
SELECT dataset_id
FROM reporting.approved_dataset
WHERE product_code IN ('CORN','RICE','SOYBEAN')
  AND left(period_code,4)='2026';
ALTER TABLE target_dataset ADD PRIMARY KEY(dataset_id);

CREATE TEMP TABLE target_preview AS
SELECT preview_id FROM reporting.report_preview
WHERE dataset_id IN (SELECT dataset_id FROM target_dataset);
ALTER TABLE target_preview ADD PRIMARY KEY(preview_id);

CREATE TEMP TABLE target_export AS
SELECT export_task_id FROM reporting.report_export_task
WHERE preview_id IN (SELECT preview_id FROM target_preview);
ALTER TABLE target_export ADD PRIMARY KEY(export_task_id);

CREATE TEMP TABLE target_publication AS
SELECT publication_id FROM reporting.report_publication
WHERE preview_id IN (SELECT preview_id FROM target_preview)
   OR export_task_id IN (SELECT export_task_id FROM target_export);
ALTER TABLE target_publication ADD PRIMARY KEY(publication_id);

CREATE TEMP TABLE target_source_release AS
SELECT source_release_id FROM supply.source_release
WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN');
ALTER TABLE target_source_release ADD PRIMARY KEY(source_release_id);

CREATE TEMP TABLE target_input_set AS
SELECT input_set_id FROM supply.source_adoption_set
WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN');
ALTER TABLE target_input_set ADD PRIMARY KEY(input_set_id);

CREATE TEMP TABLE target_calculation_run AS
SELECT calculation_run_id FROM supply.calculation_run
WHERE (survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN'))
   OR input_set_id IN (SELECT input_set_id FROM target_input_set);
ALTER TABLE target_calculation_run ADD PRIMARY KEY(calculation_run_id);

CREATE TEMP TABLE target_result_version AS
SELECT result_version_id FROM supply.result_version
WHERE (survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN'))
   OR calculation_run_id IN (SELECT calculation_run_id FROM target_calculation_run);
ALTER TABLE target_result_version ADD PRIMARY KEY(result_version_id);

CREATE TEMP TABLE target_manual_input AS
SELECT manual_input_id FROM supply.manual_input_decision
WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN');
ALTER TABLE target_manual_input ADD PRIMARY KEY(manual_input_id);

CREATE TEMP TABLE target_adjustment AS
SELECT adjustment_id FROM supply.approved_adjustment
WHERE survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN');
ALTER TABLE target_adjustment ADD PRIMARY KEY(adjustment_id);

CREATE TEMP TABLE target_adoption_decision AS
SELECT adoption_decision_id FROM supply.adoption_decision
WHERE (survey_year=2026 AND product_code IN ('CORN','RICE','SOYBEAN'))
   OR source_release_id IN (SELECT source_release_id FROM target_source_release);
ALTER TABLE target_adoption_decision ADD PRIMARY KEY(adoption_decision_id);

CREATE TEMP TABLE target_annual_network(network_year integer PRIMARY KEY);
DO $$
BEGIN
    IF to_regclass('registry.sample_network_year') IS NOT NULL THEN
        EXECUTE 'INSERT INTO target_annual_network '
             || 'SELECT network_year FROM registry.sample_network_year WHERE network_year=2026';
    END IF;
END;
$$;

CREATE TEMP TABLE reset_manifest(kind text NOT NULL,id text NOT NULL,PRIMARY KEY(kind,id));
INSERT INTO reset_manifest
SELECT 'PRODUCTION_RECORD',record_id FROM target_production
UNION ALL SELECT 'MARKET_RECORD',record_id FROM target_market
UNION ALL SELECT 'IMPORT_DRAFT',import_draft_id::text FROM target_draft
UNION ALL SELECT 'IMPORT_ROW',import_job_id::text||':'||row_number FROM target_import_row
UNION ALL SELECT 'WORK_ITEM',work_item_id::text FROM target_work_item
UNION ALL SELECT 'EVIDENCE_PHOTO',photo_id::text FROM target_photo
UNION ALL SELECT 'REPORT_DATASET',dataset_id::text FROM target_dataset
UNION ALL SELECT 'REPORT_PREVIEW',preview_id::text FROM target_preview
UNION ALL SELECT 'REPORT_EXPORT',export_task_id::text FROM target_export
UNION ALL SELECT 'REPORT_PUBLICATION',publication_id::text FROM target_publication
UNION ALL SELECT 'SUPPLY_RELEASE',source_release_id::text FROM target_source_release
UNION ALL SELECT 'SUPPLY_INPUT_SET',input_set_id::text FROM target_input_set
UNION ALL SELECT 'SUPPLY_RUN',calculation_run_id::text FROM target_calculation_run
UNION ALL SELECT 'SUPPLY_RESULT',result_version_id::text FROM target_result_version
UNION ALL SELECT 'SUPPLY_MANUAL',manual_input_id::text FROM target_manual_input
UNION ALL SELECT 'SUPPLY_ADJUSTMENT',adjustment_id::text FROM target_adjustment
UNION ALL SELECT 'SUPPLY_ADOPTION',adoption_decision_id::text FROM target_adoption_decision
UNION ALL SELECT 'ANNUAL_NETWORK',network_year::text FROM target_annual_network;

CREATE TEMP TABLE reset_digest AS
SELECT encode(sha256(convert_to(COALESCE(string_agg(kind||':'||id,E'\n' ORDER BY kind,id),''),'UTF8')),'hex') digest
FROM reset_manifest;

CREATE TEMP TABLE active_target_hold AS
SELECT hold.hold_id,hold.resource_type,hold.resource_id,hold.reason
FROM platform.data_legal_hold hold
WHERE hold.released_at IS NULL
  AND hold.resource_id IN (SELECT id FROM reset_manifest);

SELECT kind,count(*) AS row_count FROM reset_manifest GROUP BY kind ORDER BY kind;
SELECT digest AS reset_digest FROM reset_digest;
SELECT count(*) AS active_legal_hold_count FROM active_target_hold;

\if :apply
DO $$
DECLARE
    expected text;
    actual text;
    hold_count bigint;
BEGIN
    SELECT expected_digest INTO expected FROM reset_control;
    SELECT digest INTO actual FROM reset_digest;
    SELECT count(*) INTO hold_count FROM active_target_hold;
    IF expected IS NULL OR expected='' OR expected<>actual THEN
        RAISE EXCEPTION 'reset digest mismatch: expected %, actual %',expected,actual;
    END IF;
    IF hold_count<>0 THEN
        RAISE EXCEPTION 'reset blocked by % active legal hold(s)',hold_count;
    END IF;
END;
$$;

DO $$
BEGIN
    IF to_regclass('registry.sample_network_year') IS NOT NULL THEN
        EXECUTE 'DELETE FROM registry.sample_network_membership WHERE network_year=2026';
        EXECUTE 'DELETE FROM registry.sample_network_year WHERE network_year=2026';
    END IF;
END;
$$;

DELETE FROM reporting.report_publication
WHERE publication_id IN (SELECT publication_id FROM target_publication);
DELETE FROM reporting.report_export_task
WHERE export_task_id IN (SELECT export_task_id FROM target_export);
DELETE FROM reporting.report_preview
WHERE preview_id IN (SELECT preview_id FROM target_preview);
DELETE FROM reporting.approved_dataset
WHERE dataset_id IN (SELECT dataset_id FROM target_dataset);

DELETE FROM supply.result_version
WHERE result_version_id IN (SELECT result_version_id FROM target_result_version);
DELETE FROM supply.calculation_source_reference
WHERE calculation_run_id IN (SELECT calculation_run_id FROM target_calculation_run)
   OR source_release_id IN (SELECT source_release_id FROM target_source_release);
DELETE FROM supply.adoption_decision
WHERE adoption_decision_id IN (SELECT adoption_decision_id FROM target_adoption_decision);
DELETE FROM supply.source_adoption_set_item
WHERE input_set_id IN (SELECT input_set_id FROM target_input_set)
   OR source_release_id IN (SELECT source_release_id FROM target_source_release);
DELETE FROM supply.calculation_run
WHERE calculation_run_id IN (SELECT calculation_run_id FROM target_calculation_run);
DELETE FROM supply.source_adoption_set
WHERE input_set_id IN (SELECT input_set_id FROM target_input_set);
DELETE FROM supply.source_release_binding
WHERE source_release_id IN (SELECT source_release_id FROM target_source_release);
DELETE FROM supply.source_release_value
WHERE source_release_id IN (SELECT source_release_id FROM target_source_release);
DELETE FROM supply.source_release
WHERE source_release_id IN (SELECT source_release_id FROM target_source_release);
DELETE FROM supply.manual_input_decision
WHERE manual_input_id IN (SELECT manual_input_id FROM target_manual_input);
DELETE FROM supply.approved_adjustment
WHERE adjustment_id IN (SELECT adjustment_id FROM target_adjustment);

DELETE FROM workflow.work_item
WHERE work_item_id IN (SELECT work_item_id FROM target_work_item);

DELETE FROM platform.business_import_draft_evidence
WHERE import_draft_id IN (SELECT import_draft_id FROM target_draft)
   OR photo_id IN (SELECT photo_id FROM target_photo);
DELETE FROM platform.import_job_photo
WHERE photo_id IN (SELECT photo_id FROM target_photo);
DELETE FROM evidence.evidence_photo
WHERE photo_id IN (SELECT photo_id FROM target_photo);
DELETE FROM platform.import_row_result row
USING target_import_row target
WHERE row.import_job_id=target.import_job_id AND row.row_number=target.row_number;
DELETE FROM platform.business_import_draft
WHERE import_draft_id IN (SELECT import_draft_id FROM target_draft);

DELETE FROM production.production_record
WHERE record_id IN (SELECT record_id FROM target_production);
DELETE FROM market.market_record
WHERE record_id IN (SELECT record_id FROM target_market);

DO $$
DECLARE
    mismatch text;
    target_remaining bigint;
    network_remaining bigint := 0;
BEGIN
    SELECT string_agg(before.code,',' ORDER BY before.code) INTO mismatch
    FROM protected_count_before before
    CROSS JOIN LATERAL (
      SELECT CASE before.code
        WHEN 'production_other' THEN (SELECT count(*) FROM production.production_record)
        WHEN 'market_other' THEN (SELECT count(*) FROM market.market_record)
        WHEN 'draft_other' THEN (SELECT count(*) FROM platform.business_import_draft)
        WHEN 'village' THEN (SELECT count(*) FROM platform.region WHERE administrative_level='VILLAGE')
        WHEN 'village_location' THEN (
          SELECT count(*) FROM platform.region village
          JOIN platform.region_location location ON location.region_code=village.code
          WHERE village.administrative_level='VILLAGE')
        WHEN 'sample_point' THEN (SELECT count(*) FROM registry.sample_point)
        WHEN 'import_job' THEN (SELECT count(*) FROM platform.import_job)
        WHEN 'security_user' THEN (SELECT count(*) FROM platform.security_user)
        WHEN 'business_audit' THEN (SELECT count(*) FROM platform.business_audit_event)
        WHEN 'report_audit' THEN (SELECT count(*) FROM reporting.report_audit_event)
      END value
    ) after
    WHERE (before.code IN ('business_audit','report_audit') AND after.value<before.value)
       OR (before.code NOT IN ('business_audit','report_audit') AND after.value<>before.value);

    SELECT
      (SELECT count(*) FROM production.production_record
       WHERE record_id IN (SELECT record_id FROM target_production))
      +(SELECT count(*) FROM market.market_record
        WHERE record_id IN (SELECT record_id FROM target_market))
      +(SELECT count(*) FROM platform.business_import_draft
        WHERE import_draft_id IN (SELECT import_draft_id FROM target_draft))
      +(SELECT count(*) FROM platform.import_row_result row
        JOIN target_import_row target
          ON target.import_job_id=row.import_job_id AND target.row_number=row.row_number)
      +(SELECT count(*) FROM workflow.work_item
        WHERE work_item_id IN (SELECT work_item_id FROM target_work_item))
      +(SELECT count(*) FROM evidence.evidence_photo
        WHERE photo_id IN (SELECT photo_id FROM target_photo))
      +(SELECT count(*) FROM reporting.approved_dataset
        WHERE dataset_id IN (SELECT dataset_id FROM target_dataset))
      +(SELECT count(*) FROM reporting.report_preview
        WHERE preview_id IN (SELECT preview_id FROM target_preview))
      +(SELECT count(*) FROM reporting.report_export_task
        WHERE export_task_id IN (SELECT export_task_id FROM target_export))
    INTO target_remaining;

    IF to_regclass('registry.sample_network_year') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM registry.sample_network_year WHERE network_year=2026'
        INTO network_remaining;
        target_remaining := target_remaining + network_remaining;
    END IF;

    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'protected count changed: %',mismatch;
    END IF;
    IF target_remaining<>0 THEN
        RAISE EXCEPTION 'target operational rows remain after reset: %',target_remaining;
    END IF;
    IF (SELECT value FROM protected_count_before WHERE code='village')<>2332
       OR (SELECT value FROM protected_count_before WHERE code='village_location')<>2332 THEN
        RAISE EXCEPTION 'village reference baseline is incomplete; expected 2332 governed village locations';
    END IF;
END;
$$;

COMMIT;
\else
ROLLBACK;
\endif
