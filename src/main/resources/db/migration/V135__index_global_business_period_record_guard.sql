-- Supports the global current-record lookup performed under the import period advisory lock.
-- This migration changes no business rows.

CREATE INDEX production_record_period_guard_idx
    ON production.production_record(
        product_code,object_type_code,region_code,survey_year,survey_month,status_code,record_id)
    WHERE status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED');

CREATE INDEX production_record_sample_name_period_guard_idx
    ON production.production_record_submission_metadata(
        lower(regexp_replace(normalize(value,NFKC),'[[:space:]　]+','','g')),record_id)
    WHERE field_code='PROD_SAMPLE_NAME';

CREATE INDEX production_record_sample_contact_period_guard_idx
    ON production.production_record_submission_metadata(
        lower(regexp_replace(normalize(value,NFKC),'[[:space:]　()（）-]+','','g')),record_id)
    WHERE field_code='PROD_SAMPLE_CONTACT';

CREATE INDEX market_record_period_guard_idx
    ON market.market_record(
        product_code,object_type_code,region_code,survey_year,survey_month,status_code,record_id)
    WHERE status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED');

CREATE INDEX market_record_sample_name_period_guard_idx
    ON market.market_record_core_value(
        lower(regexp_replace(normalize(value,NFKC),'[[:space:]　]+','','g')),record_id)
    WHERE field_code='MKT_SAMPLE_NAME';

CREATE INDEX market_record_sample_contact_period_guard_idx
    ON market.market_record_core_value(
        lower(regexp_replace(normalize(value,NFKC),'[[:space:]　()（）-]+','','g')),record_id)
    WHERE field_code='MKT_SAMPLE_CONTACT';

CREATE INDEX logistics_route_event_period_guard_idx
    ON logistics.route_event(
        product_code,business_region_code,survey_year,survey_month,
        lower(regexp_replace(normalize(source_organization,NFKC),'[[:space:]　]+','','g')),
        lower(regexp_replace(normalize(sample_contact,NFKC),'[[:space:]　()（）-]+','','g')),
        status_code,event_id)
    WHERE status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED');
