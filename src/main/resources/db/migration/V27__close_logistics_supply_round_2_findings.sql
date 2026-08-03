-- Task 7 review round 2: confirmed source semantics, explicit immutable input sets,
-- immutable formula snapshots, and shared logistics action/display policy.

CREATE TABLE supply.role_source_applicability (
    mapping_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mapping_version integer NOT NULL CHECK (mapping_version > 0),
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    role_code varchar(80) NOT NULL REFERENCES supply.account_input_role(role_code),
    source_domain varchar(30) NOT NULL CHECK (source_domain IN ('PRODUCTION','LOGISTICS','MANUAL')),
    source_field_code varchar(80) NOT NULL CHECK (btrim(source_field_code) <> ''),
    source_unit_code varchar(40) NOT NULL CHECK (btrim(source_unit_code) <> ''),
    account_unit_code varchar(40) NOT NULL CHECK (btrim(account_unit_code) <> ''),
    conversion_rule varchar(30) NOT NULL CHECK (conversion_rule = 'MULTIPLY'),
    conversion_factor numeric(24,12) NOT NULL CHECK (conversion_factor > 0),
    required_direction_code varchar(20) CHECK (required_direction_code IN ('INFLOW','OUTFLOW','TRANSIT')),
    active boolean NOT NULL DEFAULT true,
    UNIQUE (mapping_version,product_code,role_code,source_domain,source_field_code,source_unit_code)
);

INSERT INTO supply.role_source_applicability(
    mapping_version,product_code,role_code,source_domain,source_field_code,source_unit_code,
    account_unit_code,conversion_rule,conversion_factor,required_direction_code)
SELECT 1,product.code,'LOCAL_PRODUCTION','PRODUCTION','PROD_ESTIMATED_OUTPUT','公斤',
       '万吨','MULTIPLY',0.000000100000,NULL
FROM platform.product product
UNION ALL
SELECT 1,product.code,direction.role_code,'LOGISTICS','ROUTE_VOLUME',unit.source_unit,
       '万吨','MULTIPLY',unit.factor,direction.direction_code
FROM platform.product product
CROSS JOIN (VALUES ('EXTERNAL_INFLOW','INFLOW'),('EXTERNAL_OUTFLOW','OUTFLOW'))
    direction(role_code,direction_code)
CROSS JOIN (VALUES ('吨',0.000100000000::numeric),('万吨',1.000000000000::numeric))
    unit(source_unit,factor)
UNION ALL
SELECT 1,product.code,role.role_code,'MANUAL','MANUAL_APPROVED_VALUE','万吨',
       '万吨','MULTIPLY',1.000000000000,NULL
FROM platform.product product CROSS JOIN supply.account_input_role role;

ALTER TABLE supply.source_release_binding
    ADD COLUMN mapping_id bigint REFERENCES supply.role_source_applicability(mapping_id),
    ADD COLUMN mapping_version integer,
    ADD COLUMN source_raw_value numeric(22,4),
    ADD COLUMN source_unit_code varchar(40),
    ADD COLUMN conversion_rule_snapshot varchar(30),
    ADD COLUMN conversion_factor_snapshot numeric(24,12);

DROP TRIGGER source_release_binding_validate ON supply.source_release_binding;
DROP FUNCTION supply.validate_source_release_binding();
CREATE FUNCTION supply.validate_source_release_binding_v27() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    release_row supply.source_release%ROWTYPE;
    mapping_row supply.role_source_applicability%ROWTYPE;
    actual_value numeric(22,4);
    actual_unit varchar(40);
    actual_direction varchar(20);
BEGIN
    SELECT * INTO release_row FROM supply.source_release WHERE source_release_id=NEW.source_release_id;
    SELECT * INTO mapping_row FROM supply.role_source_applicability WHERE mapping_id=NEW.mapping_id;
    IF release_row.approval_state <> 'APPROVED' OR mapping_row.mapping_id IS NULL
       OR NOT mapping_row.active OR mapping_row.mapping_version <> NEW.mapping_version
       OR mapping_row.product_code <> release_row.product_code OR mapping_row.role_code <> NEW.role_code
       OR mapping_row.source_domain <> release_row.source_domain
       OR mapping_row.source_field_code <> NEW.source_field_code
       OR mapping_row.account_unit_code <> NEW.unit_code
       OR mapping_row.conversion_rule <> NEW.conversion_rule_snapshot
       OR mapping_row.conversion_factor <> NEW.conversion_factor_snapshot THEN
        RAISE EXCEPTION 'source role semantic mapping is not confirmed';
    END IF;

    IF release_row.source_domain='PRODUCTION' AND NEW.source_field_code='PROD_ESTIMATED_OUTPUT' THEN
        SELECT record.estimated_output_kg,'公斤' INTO actual_value,actual_unit
        FROM production.production_record record
        WHERE record.record_id=release_row.source_record_id AND record.version=release_row.source_version
          AND record.product_code=release_row.product_code AND record.region_code=release_row.region_code
          AND record.status_code='APPROVED';
    ELSIF release_row.source_domain='LOGISTICS' AND NEW.source_field_code='ROUTE_VOLUME' THEN
        SELECT fact.value,fact.unit_code,event.direction_code INTO actual_value,actual_unit,actual_direction
        FROM logistics.route_event event JOIN logistics.route_fact fact ON fact.event_id=event.event_id
        WHERE event.event_id::text=release_row.source_record_id AND event.version=release_row.source_version
          AND event.product_code=release_row.product_code AND event.status_code='APPROVED'
          AND release_row.region_code IN(event.origin_region_code,event.destination_region_code)
          AND fact.fact_code=NEW.source_field_code;
    ELSIF release_row.source_domain='MANUAL' AND NEW.source_field_code='MANUAL_APPROVED_VALUE' THEN
        SELECT decision.value,decision.unit_code INTO actual_value,actual_unit
        FROM supply.manual_input_decision decision
        WHERE decision.manual_input_id::text=release_row.source_record_id
          AND decision.manual_input_id=NEW.manual_input_id AND decision.version=release_row.source_version
          AND decision.product_code=release_row.product_code AND decision.region_code=release_row.region_code
          AND decision.marketing_year=release_row.marketing_year AND decision.role_code=NEW.role_code
          AND decision.status_code='APPROVED';
    END IF;

    IF actual_value IS NULL OR actual_unit IS DISTINCT FROM mapping_row.source_unit_code
       OR actual_value IS DISTINCT FROM NEW.source_raw_value
       OR mapping_row.required_direction_code IS DISTINCT FROM actual_direction
          AND mapping_row.required_direction_code IS NOT NULL
       OR round(actual_value * mapping_row.conversion_factor,4) IS DISTINCT FROM NEW.source_value THEN
        RAISE EXCEPTION 'source provenance, unit, direction, or conversion does not match approved upstream fact';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER source_release_binding_validate_v27 BEFORE INSERT ON supply.source_release_binding
    FOR EACH ROW EXECUTE FUNCTION supply.validate_source_release_binding_v27();

CREATE TABLE supply.source_adoption_set (
    input_set_id uuid PRIMARY KEY,
    version_no bigint NOT NULL CHECK (version_no > 0),
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    marketing_year varchar(20) NOT NULL CHECK (btrim(marketing_year) <> ''),
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    created_by varchar(120) NOT NULL CHECK (btrim(created_by) <> ''),
    created_at timestamptz NOT NULL,
    UNIQUE(product_code,region_code,marketing_year,version_no)
);
CREATE TABLE supply.source_adoption_set_item (
    input_set_id uuid NOT NULL REFERENCES supply.source_adoption_set(input_set_id),
    role_code varchar(80) NOT NULL,
    source_release_id uuid NOT NULL,
    source_domain varchar(30) NOT NULL,
    source_record_id varchar(120) NOT NULL,
    source_version bigint NOT NULL,
    source_field_code varchar(80) NOT NULL,
    PRIMARY KEY(input_set_id,role_code),
    UNIQUE(input_set_id,source_release_id),
    UNIQUE(input_set_id,source_domain,source_record_id,source_version,source_field_code),
    FOREIGN KEY(source_release_id,role_code)
        REFERENCES supply.source_release_binding(source_release_id,role_code)
);
CREATE FUNCTION supply.validate_source_adoption_set() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected_set uuid:=COALESCE(NEW.input_set_id,OLD.input_set_id);
BEGIN
    IF EXISTS(SELECT 1 FROM supply.source_adoption_set adoption_set WHERE adoption_set.input_set_id=selected_set)
       AND EXISTS(
           SELECT 1 FROM supply.account_input_role role WHERE role.required
           AND NOT EXISTS(SELECT 1 FROM supply.source_adoption_set_item item
                          WHERE item.input_set_id=selected_set AND item.role_code=role.role_code)) THEN
        RAISE EXCEPTION 'source adoption set is missing required roles';
    END IF;
    IF EXISTS(
        SELECT 1 FROM supply.source_adoption_set_item item
        JOIN supply.source_adoption_set adoption_set ON adoption_set.input_set_id=item.input_set_id
        JOIN supply.source_release release ON release.source_release_id=item.source_release_id
        WHERE item.input_set_id=selected_set
          AND (release.product_code<>adoption_set.product_code OR release.region_code<>adoption_set.region_code
               OR release.marketing_year<>adoption_set.marketing_year OR release.approval_state<>'APPROVED')) THEN
        RAISE EXCEPTION 'source adoption set context does not match approved releases';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER source_adoption_set_complete AFTER INSERT ON supply.source_adoption_set
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION supply.validate_source_adoption_set();
CREATE CONSTRAINT TRIGGER source_adoption_set_item_complete AFTER INSERT ON supply.source_adoption_set_item
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION supply.validate_source_adoption_set();
CREATE TRIGGER source_adoption_set_immutable BEFORE UPDATE OR DELETE ON supply.source_adoption_set
    FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER source_adoption_set_item_immutable BEFORE UPDATE OR DELETE ON supply.source_adoption_set_item
    FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();

ALTER TABLE supply.calculation_run
    ADD COLUMN input_set_id uuid REFERENCES supply.source_adoption_set(input_set_id),
    ADD COLUMN formula_snapshot jsonb,
    ADD COLUMN adjustment_proposal_value numeric(18,4),
    ADD COLUMN adjustment_proposal_reason varchar(500),
    ADD COLUMN adjustment_requested_by varchar(120),
    ADD COLUMN adjustment_requested_at timestamptz;

UPDATE supply.calculation_run run SET formula_snapshot=jsonb_build_object(
    'code',formula.code,'version',formula.version_no,'name',formula.name,
    'precision',formula.precision_value,'scale',formula.scale_value,
    'roundingMode',formula.rounding_mode,'tolerance',formula.tolerance,
    'results',COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'role',result.result_role,'label',result.label,'required',result.required,'order',result.sort_order,
        'expression',COALESCE(expression.expression,''),
        'terms',COALESCE((SELECT jsonb_agg(jsonb_build_object(
            'operandRole',term.operand_role,'coefficient',term.coefficient,'order',term.term_order)
            ORDER BY term.term_order) FROM supply.formula_term term
            WHERE term.formula_version_id=result.formula_version_id
              AND term.result_role=result.result_role),'[]'::jsonb)) ORDER BY result.sort_order)
        FROM supply.formula_result_role result
        LEFT JOIN supply.formula_expression expression
          ON expression.formula_version_id=result.formula_version_id
         AND expression.result_code=result.result_role
        WHERE result.formula_version_id=formula.formula_version_id),'[]'::jsonb))
FROM supply.formula_version formula WHERE formula.formula_version_id=run.formula_version_id;
ALTER TABLE supply.calculation_run ALTER COLUMN formula_snapshot SET NOT NULL;

UPDATE supply.calculation_run SET
    adjustment_proposal_value=approved_adjustment,
    adjustment_proposal_reason=adjustment_reason_snapshot,
    adjustment_requested_by=created_by,
    adjustment_requested_at=created_at,
    adjustment_reason_snapshot=NULL,
    adjustment_actor_snapshot=NULL,
    adjustment_decided_at_snapshot=NULL
WHERE result_state<>'FORMAL';
ALTER TABLE supply.calculation_run ADD CONSTRAINT calculation_run_adjustment_meaning CHECK (
    (result_state='FORMAL' AND adjustment_proposal_value IS NULL
        AND adjustment_proposal_reason IS NULL AND adjustment_requested_by IS NULL AND adjustment_requested_at IS NULL)
 OR (result_state<>'FORMAL' AND adjustment_reason_snapshot IS NULL
        AND adjustment_actor_snapshot IS NULL AND adjustment_decided_at_snapshot IS NULL));

CREATE FUNCTION supply.require_explicit_run_snapshots() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.input_set_id IS NULL OR NEW.formula_snapshot IS NULL THEN
        RAISE EXCEPTION 'new calculation runs require explicit input set and formula snapshot';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER calculation_run_requires_snapshots BEFORE INSERT ON supply.calculation_run
    FOR EACH ROW EXECUTE FUNCTION supply.require_explicit_run_snapshots();

CREATE FUNCTION supply.reject_referenced_formula_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected_formula bigint:=COALESCE(OLD.formula_version_id,NEW.formula_version_id);
BEGIN
    IF EXISTS(SELECT 1 FROM supply.calculation_run WHERE formula_version_id=selected_formula) THEN
        RAISE EXCEPTION 'formula version referenced by a calculation run is immutable; create a new version';
    END IF;
    RETURN COALESCE(NEW,OLD);
END $$;
CREATE TRIGGER formula_version_referenced_immutable BEFORE UPDATE OR DELETE ON supply.formula_version
    FOR EACH ROW EXECUTE FUNCTION supply.reject_referenced_formula_change();
CREATE TRIGGER formula_result_role_referenced_immutable BEFORE UPDATE OR DELETE ON supply.formula_result_role
    FOR EACH ROW EXECUTE FUNCTION supply.reject_referenced_formula_change();
CREATE TRIGGER formula_term_referenced_immutable BEFORE UPDATE OR DELETE ON supply.formula_term
    FOR EACH ROW EXECUTE FUNCTION supply.reject_referenced_formula_change();

INSERT INTO platform.page_action(product_code,business_domain,page_kind,code,label,action_scope,sort_order)
SELECT product.code,'LOGISTICS','MONITORING','SAVE','保存草稿','ROW',25 FROM platform.product product;
CREATE TABLE platform.logistics_action_applicability (
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    status_code varchar(30) NOT NULL CHECK(status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')),
    action_code varchar(40) NOT NULL,
    PRIMARY KEY(product_code,status_code,action_code)
);
INSERT INTO platform.logistics_action_applicability(product_code,status_code,action_code)
SELECT product.code,policy.status_code,policy.action_code FROM platform.product product
CROSS JOIN (VALUES
    ('DRAFT','VIEW'),('DRAFT','SAVE'),('DRAFT','SUBMIT'),
    ('PENDING_REVIEW','VIEW'),('PENDING_REVIEW','APPROVE'),('PENDING_REVIEW','RETURN'),
    ('RETURNED','VIEW'),('RETURNED','SAVE'),('APPROVED','VIEW')) policy(status_code,action_code);

INSERT INTO platform.logistics_core_field_option(field_code,value,label,sort_order) VALUES
    ('LOG_STATUS','DRAFT','草稿',10),('LOG_STATUS','PENDING_REVIEW','待审核',20),
    ('LOG_STATUS','APPROVED','已审核',30),('LOG_STATUS','RETURNED','退回补充',40);

COMMENT ON TABLE supply.role_source_applicability IS
    'Versioned confirmed semantic/unit mappings; absence is a fail-closed business decision.';
COMMENT ON TABLE supply.source_adoption_set IS
    'Explicit immutable calculation input selection; new source choices create a new version.';
COMMENT ON COLUMN supply.calculation_run.formula_snapshot IS
    'Complete immutable calculation-time DAG and presentation metadata; history never rejoins formula tables.';
