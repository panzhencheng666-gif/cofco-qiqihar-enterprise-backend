-- Task 7 review round 1: executable formula metadata, controlled source provenance,
-- immutable calculation snapshots, and database-owned logistics write definitions.

ALTER TABLE supply.formula_version ADD COLUMN rounding_mode varchar(30) NOT NULL DEFAULT 'HALF_UP'
    CHECK (rounding_mode IN ('HALF_UP','HALF_EVEN','DOWN','UP'));
CREATE TABLE supply.formula_result_role (
 formula_version_id bigint NOT NULL REFERENCES supply.formula_version(formula_version_id) ON DELETE CASCADE,
 result_role varchar(80) NOT NULL,label varchar(160) NOT NULL,required boolean NOT NULL,sort_order integer NOT NULL,
 PRIMARY KEY(formula_version_id,result_role),UNIQUE(formula_version_id,sort_order));
CREATE TABLE supply.formula_term (
 formula_version_id bigint NOT NULL,result_role varchar(80) NOT NULL,operand_role varchar(80) NOT NULL,
 coefficient numeric(18,6) NOT NULL CHECK(coefficient<>0),term_order integer NOT NULL,
 PRIMARY KEY(formula_version_id,result_role,operand_role),UNIQUE(formula_version_id,result_role,term_order),
 FOREIGN KEY(formula_version_id,result_role) REFERENCES supply.formula_result_role(formula_version_id,result_role) ON DELETE CASCADE);
INSERT INTO supply.formula_result_role(formula_version_id,result_role,label,required,sort_order)
SELECT formula_version_id,r.code,r.label,true,r.sort_order FROM supply.formula_version CROSS JOIN (VALUES
 ('TOTAL_SUPPLY','总供给',10),('TOTAL_USE','总使用',20),('CALCULATED_ENDING_INVENTORY','计算期末库存',30),
 ('ADOPTED_ENDING_INVENTORY','采用后账面期末库存',40),
 ('INVENTORY_RECONCILIATION_DIFFERENCE','库存核对差额（调查期末库存－采用后账面期末库存）',50)) r(code,label,sort_order);
INSERT INTO supply.formula_term(formula_version_id,result_role,operand_role,coefficient,term_order)
SELECT formula_version_id,t.result_role,t.operand_role,t.coefficient,t.term_order FROM supply.formula_version CROSS JOIN (VALUES
 ('TOTAL_SUPPLY','OPENING_INVENTORY',1,10),('TOTAL_SUPPLY','LOCAL_PRODUCTION',1,20),('TOTAL_SUPPLY','EXTERNAL_INFLOW',1,30),
 ('TOTAL_SUPPLY','IMPORTS',1,40),('TOTAL_SUPPLY','OTHER_SUPPLY',1,50),('TOTAL_USE','FOOD_USE',1,10),
 ('TOTAL_USE','FEED_USE',1,20),('TOTAL_USE','SEED_USE',1,30),('TOTAL_USE','PROCESSING_USE',1,40),
 ('TOTAL_USE','LOSS',1,50),('TOTAL_USE','EXTERNAL_OUTFLOW',1,60),('TOTAL_USE','EXPORTS',1,70),('TOTAL_USE','OTHER_USE',1,80),
 ('CALCULATED_ENDING_INVENTORY','TOTAL_SUPPLY',1,10),('CALCULATED_ENDING_INVENTORY','TOTAL_USE',-1,20),
 ('ADOPTED_ENDING_INVENTORY','CALCULATED_ENDING_INVENTORY',1,10),('ADOPTED_ENDING_INVENTORY','APPROVED_ADJUSTMENT',1,20),
 ('INVENTORY_RECONCILIATION_DIFFERENCE','SURVEYED_ENDING_INVENTORY',1,10),
 ('INVENTORY_RECONCILIATION_DIFFERENCE','ADOPTED_ENDING_INVENTORY',-1,20)) t(result_role,operand_role,coefficient,term_order);

CREATE TABLE supply.manual_input_decision (
 manual_input_id uuid PRIMARY KEY,product_code varchar(40) NOT NULL REFERENCES platform.product(code),
 region_code varchar(12) NOT NULL REFERENCES platform.region(code),marketing_year varchar(20) NOT NULL CHECK(btrim(marketing_year)<>''),
 role_code varchar(80) NOT NULL REFERENCES supply.account_input_role(role_code),value numeric(18,4) NOT NULL,
 unit_code varchar(40) NOT NULL CHECK(btrim(unit_code)<>''),reason varchar(500) NOT NULL CHECK(btrim(reason)<>''),
 status_code varchar(30) NOT NULL CHECK(status_code IN('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')),
 decided_by varchar(120) NOT NULL CHECK(btrim(decided_by)<>''),approved_at timestamptz,version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK((status_code='APPROVED')=(approved_at IS NOT NULL)),UNIQUE(product_code,region_code,marketing_year,role_code,version));
ALTER TABLE supply.source_release DROP CONSTRAINT source_release_source_domain_check;
ALTER TABLE supply.source_release ADD CONSTRAINT source_release_source_domain_check
 CHECK(source_domain IN('PRODUCTION','MARKET','LOGISTICS','MANUAL','SUPPLY'));
CREATE TABLE supply.source_release_binding (
 source_release_id uuid NOT NULL REFERENCES supply.source_release(source_release_id),role_code varchar(80) NOT NULL REFERENCES supply.account_input_role(role_code),
 source_field_code varchar(80) NOT NULL CHECK(btrim(source_field_code)<>''),source_value numeric(18,4) NOT NULL,
 unit_code varchar(40) NOT NULL CHECK(btrim(unit_code)<>''),manual_input_id uuid REFERENCES supply.manual_input_decision(manual_input_id),
 PRIMARY KEY(source_release_id,role_code));
INSERT INTO supply.source_release_binding(source_release_id,role_code,source_field_code,source_value,unit_code)
SELECT source_release_id,role_code,'LEGACY_UNVERIFIED',value,unit_code FROM supply.source_release_value;

CREATE FUNCTION supply.validate_source_release_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r supply.source_release%ROWTYPE; actual numeric(18,4); matched boolean:=false;
BEGIN SELECT * INTO r FROM supply.source_release WHERE source_release_id=NEW.source_release_id;
 IF r.approval_state<>'APPROVED' THEN RAISE EXCEPTION 'source release is not approved'; END IF;
 IF r.source_domain='PRODUCTION' THEN
  SELECT CASE NEW.source_field_code WHEN 'PROD_ESTIMATED_OUTPUT' THEN p.estimated_output_kg ELSE NULL END INTO actual
  FROM production.production_record p WHERE p.record_id=r.source_record_id AND p.version=r.source_version
   AND p.product_code=r.product_code AND p.region_code=r.region_code AND p.status_code='APPROVED';
  IF actual IS NULL THEN SELECT f.value INTO actual FROM production.production_record p JOIN
   (SELECT record_id,quality_code code,value FROM production.production_record_quality UNION ALL SELECT record_id,cost_code,value FROM production.production_record_cost
    UNION ALL SELECT record_id,insurance_code,value FROM production.production_record_insurance UNION ALL SELECT record_id,subsidy_code,value FROM production.production_record_subsidy) f
   ON f.record_id=p.record_id WHERE p.record_id=r.source_record_id AND p.version=r.source_version AND p.product_code=r.product_code
    AND p.region_code=r.region_code AND p.status_code='APPROVED' AND f.code=NEW.source_field_code; END IF; matched:=actual IS NOT NULL;
 ELSIF r.source_domain='MARKET' THEN
  SELECT CASE NEW.source_field_code WHEN 'MKT_ACTUAL_TRADE_PRICE' THEN m.actual_trade_price ELSE NULL END INTO actual
  FROM market.market_record m WHERE m.record_id=r.source_record_id AND m.version=r.source_version AND m.product_code=r.product_code
   AND m.region_code=r.region_code AND m.status_code='APPROVED';
  IF actual IS NULL THEN SELECT f.value INTO actual FROM market.market_record m JOIN market.market_record_fact f ON f.record_id=m.record_id
   WHERE m.record_id=r.source_record_id AND m.version=r.source_version AND m.product_code=r.product_code AND m.region_code=r.region_code
    AND m.status_code='APPROVED' AND f.fact_code=NEW.source_field_code; END IF; matched:=actual IS NOT NULL;
 ELSIF r.source_domain='LOGISTICS' THEN
  SELECT f.value INTO actual FROM logistics.route_event e JOIN logistics.route_fact f ON f.event_id=e.event_id
  WHERE e.event_id::text=r.source_record_id AND e.version=r.source_version AND e.product_code=r.product_code AND e.status_code='APPROVED'
   AND r.region_code IN(e.origin_region_code,e.destination_region_code) AND f.fact_code=NEW.source_field_code; matched:=actual IS NOT NULL;
 ELSIF r.source_domain='MANUAL' THEN
  SELECT d.value INTO actual FROM supply.manual_input_decision d WHERE d.manual_input_id::text=r.source_record_id AND d.manual_input_id=NEW.manual_input_id
   AND d.version=r.source_version AND d.product_code=r.product_code AND d.region_code=r.region_code AND d.marketing_year=r.marketing_year
   AND d.role_code=NEW.role_code AND d.status_code='APPROVED' AND NEW.source_field_code='MANUAL_APPROVED_VALUE'; matched:=actual IS NOT NULL;
 END IF;
 IF NOT matched OR actual<>NEW.source_value THEN RAISE EXCEPTION 'source provenance does not match approved upstream version field value'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER source_release_binding_validate BEFORE INSERT OR UPDATE ON supply.source_release_binding
 FOR EACH ROW EXECUTE FUNCTION supply.validate_source_release_binding();
CREATE FUNCTION supply.reject_immutable_provenance_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'approved source provenance is immutable; create a new version'; END $$;
CREATE TRIGGER source_release_immutable BEFORE UPDATE OR DELETE ON supply.source_release FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER source_release_binding_immutable BEFORE UPDATE OR DELETE ON supply.source_release_binding FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER source_release_value_immutable BEFORE UPDATE OR DELETE ON supply.source_release_value FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();
CREATE TRIGGER manual_input_decision_immutable BEFORE UPDATE OR DELETE ON supply.manual_input_decision FOR EACH ROW EXECUTE FUNCTION supply.reject_immutable_provenance_change();

ALTER TABLE supply.calculation_source_reference ADD COLUMN source_domain_snapshot varchar(30),ADD COLUMN source_field_code_snapshot varchar(80),
 ADD COLUMN source_value_snapshot numeric(18,4),ADD COLUMN approval_state_snapshot varchar(30),ADD COLUMN approved_at_snapshot timestamptz,
 ADD COLUMN quality_state_snapshot varchar(30),ADD COLUMN role_label_snapshot varchar(160),ADD COLUMN group_code_snapshot varchar(30);
ALTER TABLE supply.calculation_source_reference ADD COLUMN unit_code_snapshot varchar(40),ADD COLUMN role_sort_order_snapshot integer;
UPDATE supply.calculation_source_reference x SET source_domain_snapshot=r.source_domain,source_field_code_snapshot=b.source_field_code,
 source_value_snapshot=b.source_value,approval_state_snapshot=r.approval_state,approved_at_snapshot=r.approved_at,
 quality_state_snapshot=r.quality_state,role_label_snapshot=role.label,group_code_snapshot=role.group_code,
 unit_code_snapshot=b.unit_code,role_sort_order_snapshot=role.sort_order
FROM supply.source_release r JOIN supply.source_release_binding b ON b.source_release_id=r.source_release_id
JOIN supply.account_input_role role ON role.role_code=b.role_code WHERE x.source_release_id=r.source_release_id AND x.role_code=b.role_code;
ALTER TABLE supply.calculation_source_reference ALTER COLUMN source_domain_snapshot SET NOT NULL,ALTER COLUMN source_field_code_snapshot SET NOT NULL,
 ALTER COLUMN source_value_snapshot SET NOT NULL,ALTER COLUMN approval_state_snapshot SET NOT NULL,ALTER COLUMN approved_at_snapshot SET NOT NULL,
 ALTER COLUMN quality_state_snapshot SET NOT NULL,ALTER COLUMN role_label_snapshot SET NOT NULL,ALTER COLUMN group_code_snapshot SET NOT NULL;
ALTER TABLE supply.calculation_source_reference ALTER COLUMN unit_code_snapshot SET NOT NULL,ALTER COLUMN role_sort_order_snapshot SET NOT NULL;
ALTER TABLE supply.calculation_run ADD COLUMN decision_version bigint NOT NULL DEFAULT 0 CHECK(decision_version>=0),
 ADD COLUMN adjustment_reason_snapshot varchar(500),ADD COLUMN adjustment_actor_snapshot varchar(120),ADD COLUMN adjustment_decided_at_snapshot timestamptz;

ALTER TABLE logistics.route_event ADD COLUMN origin_node_code varchar(80) REFERENCES logistics.logistics_node(node_code),
 ADD COLUMN destination_node_code varchar(80) REFERENCES logistics.logistics_node(node_code);
UPDATE logistics.route_event e SET origin_node_code=o.node_code,destination_node_code=d.node_code
FROM logistics.logistics_node o,logistics.logistics_node d WHERE o.node_id=e.origin_node_id AND d.node_id=e.destination_node_id;
ALTER TABLE logistics.route_event ALTER COLUMN origin_node_code SET NOT NULL,ALTER COLUMN destination_node_code SET NOT NULL,
 ALTER COLUMN origin_node_id DROP NOT NULL,ALTER COLUMN destination_node_id DROP NOT NULL;
ALTER TABLE logistics.route_event ADD CONSTRAINT route_event_distinct_node_codes CHECK(origin_node_code<>destination_node_code);

CREATE TABLE platform.logistics_core_field_definition (
 code varchar(80) PRIMARY KEY,label varchar(120) NOT NULL,control_type varchar(30) NOT NULL CHECK(control_type IN('SELECT','DATE','DECIMAL','TEXT','READONLY_DATETIME','READONLY_STATUS')),
 binding varchar(60) NOT NULL,option_source varchar(40),unit varchar(40),decimal_precision integer CHECK(decimal_precision BETWEEN 1 AND 18),
 decimal_scale integer CHECK(decimal_scale BETWEEN 0 AND decimal_precision),required boolean NOT NULL,sort_order integer NOT NULL UNIQUE,
 CHECK((control_type='DECIMAL')=(decimal_precision IS NOT NULL)),CHECK((control_type='SELECT')=(option_source IS NOT NULL)));
CREATE TABLE platform.logistics_core_field_option(field_code varchar(80) NOT NULL REFERENCES platform.logistics_core_field_definition(code) ON DELETE CASCADE,
 value varchar(80) NOT NULL,label varchar(120) NOT NULL,sort_order integer NOT NULL,PRIMARY KEY(field_code,value),UNIQUE(field_code,sort_order));
CREATE TABLE platform.logistics_core_field_applicability(field_code varchar(80) NOT NULL REFERENCES platform.logistics_core_field_definition(code),
 product_code varchar(40) NOT NULL REFERENCES platform.product(code),sort_order integer NOT NULL,PRIMARY KEY(field_code,product_code),UNIQUE(product_code,sort_order));
INSERT INTO platform.logistics_core_field_definition(code,label,control_type,binding,option_source,unit,decimal_precision,decimal_scale,required,sort_order) VALUES
 ('LOG_PERIOD','物流监测期','SELECT','EVENT.monitoring_period_code','BUSINESS_PERIOD',NULL,NULL,NULL,true,10),('LOG_COLLECTION_DATE','物流采集日期','DATE','EVENT.collection_date',NULL,NULL,NULL,NULL,true,20),
 ('LOG_ORIGIN','物流起运节点','SELECT','EVENT.origin_node_code','LOGISTICS_NODE',NULL,NULL,NULL,true,30),('LOG_DESTINATION','物流到达节点','SELECT','EVENT.destination_node_code','LOGISTICS_NODE',NULL,NULL,NULL,true,40),
 ('LOG_TRANSPORT_MODE','物流运输方式','SELECT','EVENT.transport_mode_code','TRANSPORT_MODE',NULL,NULL,NULL,true,50),('LOG_DIRECTION','物流流向类型','SELECT','EVENT.direction_code','STATIC',NULL,NULL,NULL,true,60),
 ('LOG_ROUTE_VOLUME','物流运量','DECIMAL','FACT.ROUTE_VOLUME',NULL,'吨',18,4,true,70),('LOG_FREIGHT_RATE','物流运价','DECIMAL','FACT.FREIGHT_RATE',NULL,'元/吨',18,4,true,80),
 ('LOG_TRANSIT_TIME','物流在途时间','DECIMAL','FACT.TRANSIT_TIME',NULL,'小时',18,4,true,90),('LOG_SOURCE_ORGANIZATION','物流来源单位','TEXT','EVENT.source_organization',NULL,NULL,NULL,NULL,true,100),
 ('LOG_REPORTER','物流填报人','TEXT','EVENT.reporter',NULL,NULL,NULL,NULL,true,110),('LOG_REPORTED_AT','物流填报时间','READONLY_DATETIME','READONLY.reported_at',NULL,NULL,NULL,NULL,false,120),
 ('LOG_STATUS','物流状态','READONLY_STATUS','READONLY.status_code',NULL,NULL,NULL,NULL,false,130);
INSERT INTO platform.logistics_core_field_option(field_code,value,label,sort_order) VALUES
 ('LOG_DIRECTION','INFLOW','流入',10),('LOG_DIRECTION','OUTFLOW','流出',20),('LOG_DIRECTION','TRANSIT','中转',30);
INSERT INTO platform.logistics_core_field_applicability(field_code,product_code,sort_order)
SELECT f.code,p.code,f.sort_order FROM platform.logistics_core_field_definition f CROSS JOIN platform.product p;
CREATE TABLE logistics.route_event_core_value(event_id uuid NOT NULL REFERENCES logistics.route_event(event_id) ON DELETE CASCADE,
 field_code varchar(80) NOT NULL REFERENCES platform.logistics_core_field_definition(code),value varchar(500) NOT NULL,PRIMARY KEY(event_id,field_code));
COMMENT ON TABLE supply.formula_term IS 'Ordered executable linear terms; clients never execute expression text.';
COMMENT ON TABLE supply.calculation_source_reference IS 'Immutable calculation-time source snapshot; historical reads never rejoin mutable upstream facts.';
