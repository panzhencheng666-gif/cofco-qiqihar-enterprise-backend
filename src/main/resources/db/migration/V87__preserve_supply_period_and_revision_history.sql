-- Supply temporal invariant: survey time is an immutable calendar-year
-- coordinate with an optional quarter.  Marketing year remains a separate business
-- descriptor.  Generic weekly/quarterly business periods are deliberately not
-- accepted as supply survey periods unless explicitly governed here.

CREATE TABLE platform.supply_survey_period (
    code varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    survey_year smallint NOT NULL CHECK (survey_year BETWEEN 1900 AND 2999),
    survey_quarter varchar(2) CHECK (survey_quarter IN ('Q1','Q2','Q3','Q4')),
    precision varchar(20) NOT NULL CHECK (precision IN ('YEAR','QUARTER')),
    marketing_year_code varchar(20) NOT NULL REFERENCES platform.marketing_year(code),
    sort_order integer NOT NULL UNIQUE,
    CHECK ((precision='YEAR' AND survey_quarter IS NULL)
        OR (precision='QUARTER' AND survey_quarter IS NOT NULL))
);
CREATE UNIQUE INDEX supply_survey_period_year_key
    ON platform.supply_survey_period(survey_year) WHERE survey_quarter IS NULL;
CREATE UNIQUE INDEX supply_survey_period_quarter_key
    ON platform.supply_survey_period(survey_year,survey_quarter) WHERE survey_quarter IS NOT NULL;
INSERT INTO platform.supply_survey_period(
    code,name,survey_year,survey_quarter,precision,marketing_year_code,sort_order)
VALUES
    ('2026','2026年度',2026,NULL,'YEAR','2026/27',202600),
    ('2026-Q3','2026年第三季度',2026,'Q3','QUARTER','2026/27',202603),
    ('2026-Q4','2026年第四季度',2026,'Q4','QUARTER','2026/27',202604);

-- Legacy rows are never guessed into a precision.  They remain readable and
-- explicitly pending governance until an owner supplies the missing survey coordinate.

ALTER TABLE supply.source_release
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE'));
ALTER TABLE supply.manual_input_decision
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE'));
ALTER TABLE supply.source_adoption_set
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE'));
ALTER TABLE supply.adoption_decision
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE'));
ALTER TABLE supply.approved_adjustment
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE'));
ALTER TABLE supply.calculation_run
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE'));
ALTER TABLE supply.result_version
    ADD COLUMN product_code varchar(40) REFERENCES platform.product(code),
    ADD COLUMN region_code varchar(12) REFERENCES platform.region(code),
    ADD COLUMN marketing_year varchar(20),
    ADD COLUMN period_code varchar(40) REFERENCES platform.supply_survey_period(code),
    ADD COLUMN survey_year smallint,
    ADD COLUMN survey_quarter varchar(2),
    ADD COLUMN period_precision varchar(20),
    ADD COLUMN result_state varchar(30),
    ADD COLUMN temporal_governance_state varchar(30) NOT NULL DEFAULT 'PENDING_GOVERNANCE'
        CHECK (temporal_governance_state IN ('CONFIRMED','PENDING_GOVERNANCE')),
    ADD COLUMN supersedes_result_version_id uuid REFERENCES supply.result_version(result_version_id);

-- Existing provenance tables were already immutable.  Suspend only their
-- update/delete guards for this one forward migration, then restore them below.
DROP TRIGGER source_release_immutable ON supply.source_release;
DROP TRIGGER manual_input_decision_immutable ON supply.manual_input_decision;
DROP TRIGGER source_adoption_set_immutable ON supply.source_adoption_set;

-- A source date or a weekly logistics monitoring period does not reveal whether
-- the old supply record was intended as an annual or quarterly snapshot.  No
-- source release is therefore assigned a survey precision by inference.

-- A legacy manual source can inherit a period only if every already-confirmed
-- sibling source in every adoption set that uses it points to one same period.
UPDATE supply.source_release manual_release
SET period_code = (
    SELECT min(sibling_release.period_code)
    FROM supply.source_adoption_set_item selected_item
    JOIN supply.source_adoption_set_item sibling_item
      ON sibling_item.input_set_id=selected_item.input_set_id
     AND sibling_item.source_release_id<>selected_item.source_release_id
    JOIN supply.source_release sibling_release
      ON sibling_release.source_release_id=sibling_item.source_release_id
    WHERE manual_release.source_domain='MANUAL'
      AND selected_item.source_release_id=manual_release.source_release_id
      AND sibling_release.period_code IS NOT NULL
    HAVING count(*)>0 AND count(DISTINCT sibling_release.period_code)=1
)
WHERE manual_release.source_domain='MANUAL';

UPDATE supply.manual_input_decision decision
SET period_code = (
    SELECT min(release.period_code)
    FROM supply.source_release_binding binding
    JOIN supply.source_release release ON release.source_release_id=binding.source_release_id
    WHERE binding.manual_input_id=decision.manual_input_id
      AND release.period_code IS NOT NULL
    HAVING count(DISTINCT release.period_code)=1
);

UPDATE supply.source_adoption_set adoption_set
SET period_code = (
    SELECT min(release.period_code)
    FROM supply.source_adoption_set_item item
    JOIN supply.source_release release ON release.source_release_id=item.source_release_id
    WHERE item.input_set_id=adoption_set.input_set_id
    HAVING count(*)>0 AND count(*)=count(release.period_code)
       AND count(DISTINCT release.period_code)=1
);

UPDATE supply.calculation_run run
SET period_code=adoption_set.period_code
FROM supply.source_adoption_set adoption_set
WHERE adoption_set.input_set_id=run.input_set_id
  AND adoption_set.period_code IS NOT NULL;

UPDATE supply.adoption_decision decision
SET period_code=release.period_code
FROM supply.source_release release
WHERE release.source_release_id=decision.source_release_id
  AND release.period_code IS NOT NULL;

UPDATE supply.approved_adjustment adjustment
SET period_code = (
    SELECT min(run.period_code)
    FROM supply.calculation_run run
    WHERE run.product_code=adjustment.product_code
      AND run.region_code=adjustment.region_code
      AND run.marketing_year=adjustment.marketing_year
      AND run.period_code IS NOT NULL
    HAVING count(DISTINCT run.period_code)=1
);

UPDATE supply.source_release history SET
    survey_year=period.survey_year,survey_quarter=period.survey_quarter,period_precision=period.precision
FROM platform.supply_survey_period period WHERE period.code=history.period_code;
UPDATE supply.manual_input_decision history SET
    survey_year=period.survey_year,survey_quarter=period.survey_quarter,period_precision=period.precision
FROM platform.supply_survey_period period WHERE period.code=history.period_code;
UPDATE supply.source_adoption_set history SET
    survey_year=period.survey_year,survey_quarter=period.survey_quarter,period_precision=period.precision
FROM platform.supply_survey_period period WHERE period.code=history.period_code;
UPDATE supply.adoption_decision history SET
    survey_year=period.survey_year,survey_quarter=period.survey_quarter,period_precision=period.precision
FROM platform.supply_survey_period period WHERE period.code=history.period_code;
UPDATE supply.approved_adjustment history SET
    survey_year=period.survey_year,survey_quarter=period.survey_quarter,period_precision=period.precision
FROM platform.supply_survey_period period WHERE period.code=history.period_code;
UPDATE supply.calculation_run history SET
    survey_year=period.survey_year,survey_quarter=period.survey_quarter,period_precision=period.precision
FROM platform.supply_survey_period period WHERE period.code=history.period_code;

UPDATE supply.source_release SET temporal_governance_state='CONFIRMED' WHERE period_code IS NOT NULL;
UPDATE supply.manual_input_decision SET temporal_governance_state='CONFIRMED' WHERE period_code IS NOT NULL;
UPDATE supply.source_adoption_set SET temporal_governance_state='CONFIRMED' WHERE period_code IS NOT NULL;
UPDATE supply.adoption_decision SET temporal_governance_state='CONFIRMED' WHERE period_code IS NOT NULL;
UPDATE supply.approved_adjustment SET temporal_governance_state='CONFIRMED' WHERE period_code IS NOT NULL;
UPDATE supply.calculation_run SET temporal_governance_state='CONFIRMED' WHERE period_code IS NOT NULL;

ALTER TABLE supply.calculation_run DROP CONSTRAINT calculation_run_result_state_check;
UPDATE supply.calculation_run SET result_state=CASE result_state
    WHEN 'TRIAL' THEN 'DRAFT'
    WHEN 'FORMAL_CANDIDATE' THEN 'CONFIRMED'
    WHEN 'FORMAL' THEN 'PUBLISHED'
    ELSE result_state END;
ALTER TABLE supply.calculation_run ADD CONSTRAINT calculation_run_result_state_check
    CHECK (result_state IN ('DRAFT','CONFIRMED','PUBLISHED'));
ALTER TABLE supply.calculation_run DROP CONSTRAINT calculation_run_adjustment_meaning;
ALTER TABLE supply.calculation_run ADD CONSTRAINT calculation_run_adjustment_meaning CHECK (
    (result_state='PUBLISHED' AND adjustment_proposal_value IS NULL
        AND adjustment_proposal_reason IS NULL AND adjustment_requested_by IS NULL AND adjustment_requested_at IS NULL)
 OR (result_state<>'PUBLISHED' AND adjustment_reason_snapshot IS NULL
        AND adjustment_actor_snapshot IS NULL AND adjustment_decided_at_snapshot IS NULL));

UPDATE supply.result_version version
SET product_code=run.product_code,
    region_code=run.region_code,
    marketing_year=run.marketing_year,
    period_code=run.period_code,
    survey_year=run.survey_year,
    survey_quarter=run.survey_quarter,
    period_precision=run.period_precision,
    result_state=run.result_state,
    temporal_governance_state=run.temporal_governance_state
FROM supply.calculation_run run
WHERE run.calculation_run_id=version.calculation_run_id;

UPDATE supply.result_version current_version
SET supersedes_result_version_id=previous_version.result_version_id
FROM supply.result_version previous_version
WHERE current_version.period_code IS NOT NULL
  AND previous_version.product_code=current_version.product_code
  AND previous_version.region_code=current_version.region_code
  AND previous_version.period_code=current_version.period_code
  AND previous_version.version_no=current_version.version_no-1;

ALTER TABLE supply.result_version
    ALTER COLUMN product_code SET NOT NULL,
    ALTER COLUMN region_code SET NOT NULL,
    ALTER COLUMN marketing_year SET NOT NULL,
    ALTER COLUMN result_state SET NOT NULL,
    ADD CONSTRAINT result_version_state_check CHECK (result_state IN ('DRAFT','CONFIRMED','PUBLISHED'));

-- Replace annual-current uniqueness with period-version uniqueness.  The
-- dynamic lookup avoids relying on PostgreSQL's truncated generated names.
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name FROM pg_constraint
    WHERE conrelid='supply.manual_input_decision'::regclass AND contype='u' LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE supply.manual_input_decision DROP CONSTRAINT %I',constraint_name);
    END IF;
    SELECT conname INTO constraint_name FROM pg_constraint
    WHERE conrelid='supply.source_adoption_set'::regclass AND contype='u' LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE supply.source_adoption_set DROP CONSTRAINT %I',constraint_name);
    END IF;
    SELECT conname INTO constraint_name FROM pg_constraint
    WHERE conrelid='supply.adoption_decision'::regclass AND contype='u' LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE supply.adoption_decision DROP CONSTRAINT %I',constraint_name);
    END IF;
    SELECT conname INTO constraint_name FROM pg_constraint
    WHERE conrelid='supply.approved_adjustment'::regclass AND contype='u' LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE supply.approved_adjustment DROP CONSTRAINT %I',constraint_name);
    END IF;
END $$;

ALTER TABLE supply.manual_input_decision ADD CONSTRAINT manual_input_decision_period_version_key
    UNIQUE(product_code,region_code,period_code,role_code,version);
ALTER TABLE supply.source_adoption_set ADD CONSTRAINT source_adoption_set_period_version_key
    UNIQUE(product_code,region_code,period_code,version_no);
ALTER TABLE supply.adoption_decision ADD CONSTRAINT adoption_decision_period_version_key
    UNIQUE(product_code,region_code,period_code,role_code,version);
ALTER TABLE supply.approved_adjustment ADD CONSTRAINT approved_adjustment_period_version_key
    UNIQUE(product_code,region_code,period_code,version);
CREATE UNIQUE INDEX result_version_period_version_key
    ON supply.result_version(product_code,region_code,period_code,version_no)
    WHERE period_code IS NOT NULL;

CREATE INDEX source_release_period_query
    ON supply.source_release(product_code,region_code,period_code,approved_at DESC);
CREATE INDEX calculation_run_period_query
    ON supply.calculation_run(product_code,region_code,period_code,created_at DESC);

ALTER TABLE supply.source_release ADD CONSTRAINT source_release_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));
ALTER TABLE supply.manual_input_decision ADD CONSTRAINT manual_input_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));
ALTER TABLE supply.source_adoption_set ADD CONSTRAINT source_adoption_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));
ALTER TABLE supply.adoption_decision ADD CONSTRAINT adoption_decision_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));
ALTER TABLE supply.approved_adjustment ADD CONSTRAINT approved_adjustment_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));
ALTER TABLE supply.calculation_run ADD CONSTRAINT calculation_run_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));
ALTER TABLE supply.result_version ADD CONSTRAINT result_version_period_governance_check CHECK (
    (temporal_governance_state='CONFIRMED' AND period_code IS NOT NULL AND survey_year IS NOT NULL
      AND ((period_precision='YEAR' AND survey_quarter IS NULL)
        OR (period_precision='QUARTER' AND survey_quarter IN ('Q1','Q2','Q3','Q4'))))
 OR (temporal_governance_state='PENDING_GOVERNANCE' AND period_code IS NULL AND survey_year IS NULL
      AND survey_quarter IS NULL AND period_precision IS NULL));

CREATE FUNCTION supply.require_confirmed_supply_period() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE governed_period platform.supply_survey_period%ROWTYPE;
BEGIN
    IF NEW.period_code IS NULL OR NEW.temporal_governance_state<>'CONFIRMED' THEN
        RAISE EXCEPTION 'new supply history requires a confirmed survey period';
    END IF;
    SELECT * INTO governed_period FROM platform.supply_survey_period
    WHERE code=NEW.period_code;
    IF NOT FOUND OR governed_period.marketing_year_code<>NEW.marketing_year THEN
        RAISE EXCEPTION 'supply survey period does not match the recorded marketing year';
    END IF;
    NEW.survey_year=governed_period.survey_year;
    NEW.survey_quarter=governed_period.survey_quarter;
    NEW.period_precision=governed_period.precision;
    RETURN NEW;
END $$;

CREATE TRIGGER source_release_requires_period BEFORE INSERT ON supply.source_release
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();
CREATE TRIGGER manual_input_requires_period BEFORE INSERT ON supply.manual_input_decision
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();
CREATE TRIGGER source_adoption_requires_period BEFORE INSERT ON supply.source_adoption_set
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();
CREATE TRIGGER adoption_decision_requires_period BEFORE INSERT ON supply.adoption_decision
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();
CREATE TRIGGER approved_adjustment_requires_period BEFORE INSERT ON supply.approved_adjustment
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();
CREATE TRIGGER calculation_run_requires_period BEFORE INSERT ON supply.calculation_run
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();
CREATE TRIGGER result_version_requires_period BEFORE INSERT ON supply.result_version
    FOR EACH ROW EXECUTE FUNCTION supply.require_confirmed_supply_period();

CREATE FUNCTION supply.validate_release_period_provenance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE release_row supply.source_release%ROWTYPE;
BEGIN
    SELECT * INTO release_row FROM supply.source_release WHERE source_release_id=NEW.source_release_id;
    IF release_row.source_domain='PRODUCTION' AND NOT EXISTS(
        SELECT 1 FROM production.production_record record
        JOIN platform.supply_survey_period period ON period.code=release_row.period_code
        WHERE record.record_id=release_row.source_record_id AND record.version=release_row.source_version
          AND record.product_code=release_row.product_code AND record.region_code=release_row.region_code
          AND EXTRACT(YEAR FROM record.survey_date)=period.survey_year
          AND (period.survey_quarter IS NULL
            OR period.survey_quarter='Q'||EXTRACT(QUARTER FROM record.survey_date)::integer::text)) THEN
        RAISE EXCEPTION 'production source does not belong to the supply survey period';
    ELSIF release_row.source_domain='LOGISTICS' AND NOT EXISTS(
        SELECT 1 FROM logistics.route_event event
        JOIN platform.business_period source_period ON source_period.code=event.monitoring_period_code
        JOIN platform.supply_survey_period period ON period.code=release_row.period_code
        WHERE event.event_id::text=release_row.source_record_id AND event.version=release_row.source_version
          AND event.product_code=release_row.product_code
          AND release_row.region_code IN(event.origin_region_code,event.destination_region_code)
          AND EXTRACT(YEAR FROM source_period.starts_on)=period.survey_year
          AND EXTRACT(YEAR FROM source_period.ends_on)=period.survey_year
          AND (period.survey_quarter IS NULL OR (
            period.survey_quarter='Q'||EXTRACT(QUARTER FROM source_period.starts_on)::integer::text
            AND period.survey_quarter='Q'||EXTRACT(QUARTER FROM source_period.ends_on)::integer::text))) THEN
        RAISE EXCEPTION 'logistics source does not belong to the supply survey period';
    ELSIF release_row.source_domain='MANUAL' AND NOT EXISTS(
        SELECT 1 FROM supply.manual_input_decision decision
        WHERE decision.manual_input_id=NEW.manual_input_id
          AND decision.period_code=release_row.period_code) THEN
        RAISE EXCEPTION 'manual source does not belong to the supply business period';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER source_release_binding_period_validate BEFORE INSERT ON supply.source_release_binding
    FOR EACH ROW EXECUTE FUNCTION supply.validate_release_period_provenance();

-- Append-only decision and result history.  A correction is represented by the
-- next version and the result_version predecessor link, never an in-place edit.
CREATE FUNCTION supply.reject_temporal_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'supply temporal history is immutable; create a new version';
END $$;

CREATE TRIGGER source_release_immutable BEFORE UPDATE OR DELETE ON supply.source_release
    FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER manual_input_decision_immutable BEFORE UPDATE OR DELETE ON supply.manual_input_decision
    FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER source_adoption_set_immutable BEFORE UPDATE OR DELETE ON supply.source_adoption_set
    FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER adoption_decision_history_immutable BEFORE UPDATE OR DELETE ON supply.adoption_decision
    FOR EACH ROW EXECUTE FUNCTION supply.reject_temporal_history_change();
CREATE TRIGGER approved_adjustment_history_immutable BEFORE UPDATE OR DELETE ON supply.approved_adjustment
    FOR EACH ROW EXECUTE FUNCTION supply.reject_temporal_history_change();
CREATE TRIGGER calculation_run_history_immutable BEFORE UPDATE OR DELETE ON supply.calculation_run
    FOR EACH ROW EXECUTE FUNCTION supply.reject_temporal_history_change();
CREATE TRIGGER result_version_history_immutable BEFORE UPDATE OR DELETE ON supply.result_version
    FOR EACH ROW EXECUTE FUNCTION supply.reject_temporal_history_change();

-- The generic page remains metadata-driven.  Its only temporal filter is the
-- governed survey year or year-quarter; reporting date is intentionally
-- absent from the supply page.
DELETE FROM platform.page_filter_definition
WHERE business_domain='SUPPLY' AND page_kind='ACCOUNT' AND code='marketingYear';
INSERT INTO platform.page_filter_definition(
    product_code,business_domain,page_kind,code,label,control_type,placeholder,sort_order)
SELECT product.code,'SUPPLY','ACCOUNT','periodCode','调查期间','SELECT','请选择调查年度或季度',20
FROM platform.product product;
INSERT INTO platform.page_filter_definition(
    product_code,business_domain,page_kind,code,label,control_type,placeholder,sort_order)
SELECT product.code,'SUPPLY','ACCOUNT','version','结果版本','TEXT','请输入结果版本',40
FROM platform.product product
ON CONFLICT DO NOTHING;

DELETE FROM platform.page_filter_option
WHERE business_domain='SUPPLY' AND page_kind='ACCOUNT' AND filter_code='resultState';
INSERT INTO platform.page_filter_option(
    product_code,business_domain,page_kind,filter_code,value,label,sort_order)
SELECT product.code,'SUPPLY','ACCOUNT','resultState',state.code,state.label,state.sort_order
FROM platform.product product CROSS JOIN (VALUES
    ('DRAFT','草稿',10),('CONFIRMED','已确认',20),('PUBLISHED','已发布',30)
) state(code,label,sort_order);

COMMENT ON COLUMN supply.source_release.temporal_governance_state IS
    'CONFIRMED only when survey year/quarter precision is explicit; PENDING_GOVERNANCE preserves ambiguous legacy data without guessing.';
COMMENT ON COLUMN supply.result_version.supersedes_result_version_id IS
    'Immediate predecessor in the same product, region and business-period revision chain.';
