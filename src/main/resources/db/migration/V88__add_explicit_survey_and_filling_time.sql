-- Third-stage temporal contract for production, market, and logistics records.
-- Existing business dates remain authoritative compatibility inputs. Explicit survey
-- year/month fields prevent annual records from being coerced into a fictional month.

ALTER TABLE production.production_record
    ADD COLUMN survey_year integer,
    ADD COLUMN survey_month integer,
    ADD COLUMN survey_period_precision varchar(20),
    ADD COLUMN survey_period_governance_state varchar(30),
    ADD COLUMN submitted_at timestamptz;

UPDATE production.production_record
SET survey_year = EXTRACT(YEAR FROM survey_date)::integer,
    survey_month = EXTRACT(MONTH FROM survey_date)::integer,
    survey_period_precision = 'YEAR_MONTH',
    survey_period_governance_state = 'CONFIRMED';

UPDATE production.production_record record
SET submitted_at = audit.occurred_at
FROM (
    SELECT aggregate_id, max(occurred_at) AS occurred_at
    FROM platform.business_audit_event
    WHERE aggregate_type = 'PRODUCTION_RECORD'
      AND action_code = 'PRODUCTION_RECORD_SUBMITTED'
    GROUP BY aggregate_id
) audit
WHERE audit.aggregate_id = record.record_id;

ALTER TABLE production.production_record
    ALTER COLUMN survey_year SET NOT NULL,
    ALTER COLUMN survey_period_precision SET NOT NULL,
    ALTER COLUMN survey_period_governance_state SET NOT NULL,
    ADD CONSTRAINT production_record_survey_year_valid CHECK (survey_year BETWEEN 1900 AND 2200),
    ADD CONSTRAINT production_record_survey_month_valid CHECK (survey_month BETWEEN 1 AND 12),
    ADD CONSTRAINT production_record_survey_precision_valid CHECK (
        (survey_period_precision = 'YEAR' AND survey_month IS NULL)
        OR (survey_period_precision = 'YEAR_MONTH' AND survey_month IS NOT NULL)),
    ADD CONSTRAINT production_record_survey_governance_valid CHECK (
        survey_period_governance_state IN ('CONFIRMED', 'PENDING_GOVERNANCE'));

CREATE INDEX production_record_survey_period_idx
    ON production.production_record(product_code, survey_year, survey_month, record_id);
CREATE INDEX production_record_filling_time_idx
    ON production.production_record(product_code, (COALESCE(submitted_at, created_at)));

ALTER TABLE market.market_record
    ADD COLUMN survey_year integer,
    ADD COLUMN survey_month integer,
    ADD COLUMN survey_period_precision varchar(20),
    ADD COLUMN survey_period_governance_state varchar(30),
    ADD COLUMN submitted_at timestamptz;

UPDATE market.market_record
SET survey_year = EXTRACT(YEAR FROM trade_date)::integer,
    survey_month = EXTRACT(MONTH FROM trade_date)::integer,
    survey_period_precision = 'YEAR_MONTH',
    survey_period_governance_state = 'CONFIRMED';

UPDATE market.market_record record
SET submitted_at = audit.occurred_at
FROM (
    SELECT aggregate_id, max(occurred_at) AS occurred_at
    FROM platform.business_audit_event
    WHERE aggregate_type = 'MARKET_RECORD'
      AND action_code = 'MARKET_RECORD_SUBMITTED'
    GROUP BY aggregate_id
) audit
WHERE audit.aggregate_id = record.record_id;

ALTER TABLE market.market_record
    ALTER COLUMN survey_year SET NOT NULL,
    ALTER COLUMN survey_period_precision SET NOT NULL,
    ALTER COLUMN survey_period_governance_state SET NOT NULL,
    ADD CONSTRAINT market_record_survey_year_valid CHECK (survey_year BETWEEN 1900 AND 2200),
    ADD CONSTRAINT market_record_survey_month_valid CHECK (survey_month BETWEEN 1 AND 12),
    ADD CONSTRAINT market_record_survey_precision_valid CHECK (
        (survey_period_precision = 'YEAR' AND survey_month IS NULL)
        OR (survey_period_precision = 'YEAR_MONTH' AND survey_month IS NOT NULL)),
    ADD CONSTRAINT market_record_survey_governance_valid CHECK (
        survey_period_governance_state IN ('CONFIRMED', 'PENDING_GOVERNANCE'));

CREATE INDEX market_record_survey_period_idx
    ON market.market_record(product_code, survey_year, survey_month, record_id);
CREATE INDEX market_record_filling_time_idx
    ON market.market_record(product_code, (COALESCE(submitted_at, created_at)));

ALTER TABLE logistics.route_event
    ADD COLUMN survey_year integer,
    ADD COLUMN survey_month integer,
    ADD COLUMN survey_period_precision varchar(20),
    ADD COLUMN survey_period_governance_state varchar(30),
    ADD COLUMN submitted_at timestamptz;

UPDATE logistics.route_event event
SET survey_year = EXTRACT(YEAR FROM event.collection_date)::integer,
    survey_month = CASE
        WHEN period.starts_on IS NOT NULL
         AND period.ends_on IS NOT NULL
         AND EXTRACT(YEAR FROM period.starts_on) = EXTRACT(YEAR FROM period.ends_on)
         AND EXTRACT(MONTH FROM period.starts_on) = EXTRACT(MONTH FROM period.ends_on)
         AND event.collection_date BETWEEN period.starts_on AND period.ends_on
        THEN EXTRACT(MONTH FROM event.collection_date)::integer
        ELSE NULL
    END,
    survey_period_precision = CASE
        WHEN period.starts_on IS NOT NULL
         AND period.ends_on IS NOT NULL
         AND EXTRACT(YEAR FROM period.starts_on) = EXTRACT(YEAR FROM period.ends_on)
         AND EXTRACT(MONTH FROM period.starts_on) = EXTRACT(MONTH FROM period.ends_on)
         AND event.collection_date BETWEEN period.starts_on AND period.ends_on
        THEN 'YEAR_MONTH'
        ELSE 'YEAR'
    END,
    survey_period_governance_state = CASE
        WHEN period.starts_on IS NOT NULL
         AND period.ends_on IS NOT NULL
         AND EXTRACT(YEAR FROM period.starts_on) = EXTRACT(YEAR FROM period.ends_on)
         AND EXTRACT(MONTH FROM period.starts_on) = EXTRACT(MONTH FROM period.ends_on)
         AND event.collection_date BETWEEN period.starts_on AND period.ends_on
        THEN 'CONFIRMED'
        ELSE 'PENDING_GOVERNANCE'
    END
FROM platform.business_period period
WHERE period.code = event.monitoring_period_code;

-- The FK normally guarantees a period row. This fallback keeps a damaged legacy row
-- truthful: only the collection year is known, and the record is explicitly governed later.
UPDATE logistics.route_event
SET survey_year = EXTRACT(YEAR FROM collection_date)::integer,
    survey_month = NULL,
    survey_period_precision = 'YEAR',
    survey_period_governance_state = 'PENDING_GOVERNANCE'
WHERE survey_year IS NULL;

UPDATE logistics.route_event event
SET submitted_at = audit.occurred_at
FROM (
    SELECT aggregate_id, max(occurred_at) AS occurred_at
    FROM platform.business_audit_event
    WHERE aggregate_type = 'LOGISTICS_RECORD'
      AND action_code = 'LOGISTICS_RECORD_SUBMITTED'
    GROUP BY aggregate_id
) audit
WHERE audit.aggregate_id = event.event_id::text;

ALTER TABLE logistics.route_event
    ALTER COLUMN survey_year SET NOT NULL,
    ALTER COLUMN survey_period_precision SET NOT NULL,
    ALTER COLUMN survey_period_governance_state SET NOT NULL,
    ADD CONSTRAINT route_event_survey_year_valid CHECK (survey_year BETWEEN 1900 AND 2200),
    ADD CONSTRAINT route_event_survey_month_valid CHECK (survey_month BETWEEN 1 AND 12),
    ADD CONSTRAINT route_event_survey_precision_valid CHECK (
        (survey_period_precision = 'YEAR' AND survey_month IS NULL)
        OR (survey_period_precision = 'YEAR_MONTH' AND survey_month IS NOT NULL)),
    ADD CONSTRAINT route_event_survey_governance_valid CHECK (
        survey_period_governance_state IN ('CONFIRMED', 'PENDING_GOVERNANCE'));

CREATE INDEX route_event_survey_period_idx
    ON logistics.route_event(product_code, survey_year, survey_month, event_id);
CREATE INDEX route_event_filling_time_idx
    ON logistics.route_event(product_code, (COALESCE(submitted_at, created_at)));

-- Additive page-definition entries keep legacy exact-date and business-period filters
-- valid while new clients switch to the explicit temporal contract.
INSERT INTO platform.page_filter_definition(
    product_code,business_domain,page_kind,code,label,control_type,placeholder,sort_order)
SELECT product.code, domain.code, 'MONITORING', filter.code, filter.label,
       filter.control_type, filter.placeholder, filter.sort_order
FROM platform.product product
CROSS JOIN (VALUES ('PRODUCTION'),('MARKET'),('LOGISTICS')) domain(code)
CROSS JOIN (VALUES
    ('surveyYear','调查年份','TEXT','必填，例如 2026',110),
    ('surveyMonth','调查月份','TEXT','可空，1—12 月',120),
    ('fillingDateFrom','填报日期起','DATE','请选择开始日期',130),
    ('fillingDateTo','填报日期止','DATE','请选择结束日期',140)
) filter(code,label,control_type,placeholder,sort_order);

-- Legacy dates remain in the contract, but their labels must not imply the new
-- survey-period or immutable submission-time semantics.
UPDATE platform.field_definition
SET name = CASE code
    WHEN 'PROD_SURVEY_DATE' THEN '调查日期（兼容字段）'
    WHEN 'PROD_REPORTED_AT' THEN '产情最后保存时间（兼容字段）'
    WHEN 'MKT_TRADE_DATE' THEN '交易日期（兼容字段）'
    WHEN 'MKT_REPORTED_AT' THEN '市场最后保存时间（兼容字段）'
    WHEN 'LOG_COLLECTION_DATE' THEN '采集日期（兼容字段）'
    WHEN 'LOG_REPORTED_AT' THEN '物流最后保存时间（兼容字段）'
END
WHERE code IN ('PROD_SURVEY_DATE','PROD_REPORTED_AT','MKT_TRADE_DATE',
               'MKT_REPORTED_AT','LOG_COLLECTION_DATE','LOG_REPORTED_AT');

UPDATE platform.market_core_field_definition
SET label = '最后保存时间（兼容字段）'
WHERE code = 'MKT_REPORTED_AT';

UPDATE platform.logistics_core_field_definition
SET label = '最后保存时间（兼容字段）'
WHERE code = 'LOG_REPORTED_AT';

CREATE FUNCTION production.derive_record_survey_period()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.survey_year IS NULL OR NEW.survey_period_precision IS NULL
       OR (TG_OP = 'UPDATE'
           AND NEW.survey_date IS DISTINCT FROM OLD.survey_date
           AND NEW.survey_year IS NOT DISTINCT FROM OLD.survey_year
           AND NEW.survey_month IS NOT DISTINCT FROM OLD.survey_month
           AND NEW.survey_period_precision IS NOT DISTINCT FROM OLD.survey_period_precision) THEN
        NEW.survey_year := EXTRACT(YEAR FROM NEW.survey_date)::integer;
        NEW.survey_month := EXTRACT(MONTH FROM NEW.survey_date)::integer;
        NEW.survey_period_precision := 'YEAR_MONTH';
        NEW.survey_period_governance_state := 'CONFIRMED';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER production_record_derive_survey_period
BEFORE INSERT OR UPDATE OF survey_date ON production.production_record
FOR EACH ROW EXECUTE FUNCTION production.derive_record_survey_period();

CREATE FUNCTION market.derive_record_survey_period()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.survey_year IS NULL OR NEW.survey_period_precision IS NULL
       OR (TG_OP = 'UPDATE'
           AND NEW.trade_date IS DISTINCT FROM OLD.trade_date
           AND NEW.survey_year IS NOT DISTINCT FROM OLD.survey_year
           AND NEW.survey_month IS NOT DISTINCT FROM OLD.survey_month
           AND NEW.survey_period_precision IS NOT DISTINCT FROM OLD.survey_period_precision) THEN
        NEW.survey_year := EXTRACT(YEAR FROM NEW.trade_date)::integer;
        NEW.survey_month := EXTRACT(MONTH FROM NEW.trade_date)::integer;
        NEW.survey_period_precision := 'YEAR_MONTH';
        NEW.survey_period_governance_state := 'CONFIRMED';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER market_record_derive_survey_period
BEFORE INSERT OR UPDATE OF trade_date ON market.market_record
FOR EACH ROW EXECUTE FUNCTION market.derive_record_survey_period();

CREATE FUNCTION logistics.derive_route_event_survey_period()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    period_start date;
    period_end date;
BEGIN
    IF NEW.survey_year IS NULL OR NEW.survey_period_precision IS NULL
       OR (TG_OP = 'UPDATE'
           AND (NEW.collection_date IS DISTINCT FROM OLD.collection_date
                OR NEW.monitoring_period_code IS DISTINCT FROM OLD.monitoring_period_code)
           AND NEW.survey_year IS NOT DISTINCT FROM OLD.survey_year
           AND NEW.survey_month IS NOT DISTINCT FROM OLD.survey_month
           AND NEW.survey_period_precision IS NOT DISTINCT FROM OLD.survey_period_precision) THEN
        SELECT starts_on, ends_on INTO period_start, period_end
        FROM platform.business_period WHERE code = NEW.monitoring_period_code;
        NEW.survey_year := EXTRACT(YEAR FROM NEW.collection_date)::integer;
        IF period_start IS NOT NULL
           AND period_end IS NOT NULL
           AND EXTRACT(YEAR FROM period_start) = EXTRACT(YEAR FROM period_end)
           AND EXTRACT(MONTH FROM period_start) = EXTRACT(MONTH FROM period_end)
           AND NEW.collection_date BETWEEN period_start AND period_end THEN
            NEW.survey_month := EXTRACT(MONTH FROM NEW.collection_date)::integer;
            NEW.survey_period_precision := 'YEAR_MONTH';
            NEW.survey_period_governance_state := 'CONFIRMED';
        ELSE
            NEW.survey_month := NULL;
            NEW.survey_period_precision := 'YEAR';
            NEW.survey_period_governance_state := 'PENDING_GOVERNANCE';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER route_event_derive_survey_period
BEFORE INSERT OR UPDATE OF collection_date, monitoring_period_code ON logistics.route_event
FOR EACH ROW EXECUTE FUNCTION logistics.derive_route_event_survey_period();

CREATE FUNCTION platform.capture_record_submission_time()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status_code = 'PENDING_REVIEW'
       AND OLD.status_code IS DISTINCT FROM 'PENDING_REVIEW' THEN
        NEW.submitted_at := now();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER production_record_capture_submission_time
BEFORE UPDATE OF status_code ON production.production_record
FOR EACH ROW EXECUTE FUNCTION platform.capture_record_submission_time();
CREATE TRIGGER market_record_capture_submission_time
BEFORE UPDATE OF status_code ON market.market_record
FOR EACH ROW EXECUTE FUNCTION platform.capture_record_submission_time();
CREATE TRIGGER route_event_capture_submission_time
BEFORE UPDATE OF status_code ON logistics.route_event
FOR EACH ROW EXECUTE FUNCTION platform.capture_record_submission_time();

COMMENT ON COLUMN production.production_record.submitted_at IS
    'Latest real submission time. Backfilled from immutable audit events; null means the created_at fallback remains explicit.';
COMMENT ON COLUMN production.production_record.reported_at IS
    'Mutable legacy save/report timestamp retained for compatibility; never use as a submission or creation time.';
COMMENT ON COLUMN market.market_record.submitted_at IS
    'Latest real submission time. Backfilled from immutable audit events; null means the created_at fallback remains explicit.';
COMMENT ON COLUMN market.market_record.reported_at IS
    'Mutable legacy save/report timestamp retained for compatibility; never use as a submission or creation time.';
COMMENT ON COLUMN logistics.route_event.submitted_at IS
    'Latest real submission time. Backfilled from immutable audit events; null means the created_at fallback remains explicit.';
COMMENT ON COLUMN logistics.route_event.reported_at IS
    'Mutable legacy save/report timestamp retained for compatibility; never use as a submission or creation time.';
