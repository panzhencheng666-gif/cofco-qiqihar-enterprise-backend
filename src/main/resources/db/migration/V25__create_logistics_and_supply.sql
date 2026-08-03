-- Task 7 forward-only migration. Business tables intentionally receive no records.

CREATE TABLE platform.transport_mode (
    code varchar(40) PRIMARY KEY,
    name varchar(80) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);
INSERT INTO platform.transport_mode(code, name, sort_order) VALUES
    ('RAIL', '铁路', 10), ('ROAD', '公路', 20);

INSERT INTO platform.object_type(code, name, business_domain, sort_order) VALUES
    ('RAIL_NODE', '铁路站点', 'LOGISTICS', 210),
    ('ROAD_NODE', '公路物流节点', 'LOGISTICS', 220);
INSERT INTO platform.product_object_type(product_code, object_type_code)
SELECT product.code, object_type.code
FROM platform.product product
CROSS JOIN platform.object_type object_type
WHERE object_type.business_domain = 'LOGISTICS';

CREATE TABLE logistics.logistics_node (
    node_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    node_code varchar(80) NOT NULL UNIQUE,
    node_name varchar(160) NOT NULL,
    node_type_code varchar(60) NOT NULL REFERENCES platform.object_type(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE logistics.route_event (
    event_id uuid PRIMARY KEY,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    monitoring_period_code varchar(40) NOT NULL REFERENCES platform.business_period(code),
    collection_date date NOT NULL,
    reported_at timestamptz NOT NULL,
    origin_region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    origin_node_id bigint NOT NULL REFERENCES logistics.logistics_node(node_id),
    destination_region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    destination_node_id bigint NOT NULL REFERENCES logistics.logistics_node(node_id),
    transport_mode_code varchar(40) NOT NULL REFERENCES platform.transport_mode(code),
    direction_code varchar(20) NOT NULL CHECK (direction_code IN ('INFLOW','OUTFLOW','TRANSIT')),
    source_organization varchar(160) NOT NULL CHECK (btrim(source_organization) <> ''),
    reporter varchar(120) NOT NULL CHECK (btrim(reporter) <> ''),
    status_code varchar(30) NOT NULL CHECK (status_code IN ('DRAFT','PENDING_REVIEW','APPROVED','RETURNED')),
    return_reason varchar(500),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by varchar(120) NOT NULL,
    last_modified_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (origin_node_id <> destination_node_id),
    CHECK ((status_code = 'RETURNED') = (return_reason IS NOT NULL AND btrim(return_reason) <> ''))
);
CREATE INDEX route_event_list_query ON logistics.route_event
    (product_code, status_code, collection_date DESC, event_id);

CREATE TABLE logistics.route_fact (
    event_id uuid NOT NULL REFERENCES logistics.route_event(event_id) ON DELETE CASCADE,
    fact_code varchar(40) NOT NULL CHECK (fact_code IN ('ROUTE_VOLUME','FREIGHT_RATE','TRANSIT_TIME')),
    value numeric(18,4) NOT NULL CHECK (value >= 0),
    unit_code varchar(40) NOT NULL CHECK (btrim(unit_code) <> ''),
    PRIMARY KEY(event_id, fact_code)
);
COMMENT ON TABLE logistics.route_event IS
    'One physical logistics event; transport modes are event attributes and never additive totals.';

CREATE TABLE supply.formula_version (
    formula_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0),
    name varchar(160) NOT NULL,
    precision_value integer NOT NULL CHECK (precision_value > 0),
    scale_value integer NOT NULL CHECK (scale_value >= 0 AND scale_value <= precision_value),
    tolerance numeric(18,4) NOT NULL CHECK (tolerance >= 0),
    difference_code varchar(80) NOT NULL,
    difference_label varchar(180) NOT NULL,
    difference_expression varchar(300) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    UNIQUE(code, version_no)
);
INSERT INTO supply.formula_version(
    code, version_no, name, precision_value, scale_value, tolerance,
    difference_code, difference_label, difference_expression)
VALUES ('GRAIN_BALANCE', 1, '粮食供需平衡公式', 18, 3, 0.500,
    'INVENTORY_RECONCILIATION_DIFFERENCE',
    '库存核对差额（调查期末库存－采用后账面期末库存）',
    'SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY');

CREATE TABLE supply.formula_expression (
    formula_version_id bigint NOT NULL REFERENCES supply.formula_version(formula_version_id),
    result_code varchar(80) NOT NULL,
    label varchar(160) NOT NULL,
    expression varchar(500) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY(formula_version_id, result_code),
    UNIQUE(formula_version_id, sort_order)
);
INSERT INTO supply.formula_expression(formula_version_id, result_code, label, expression, sort_order)
SELECT formula_version.formula_version_id, expression.code, expression.label, expression.formula, expression.sort_order
FROM supply.formula_version formula_version
CROSS JOIN (VALUES
 ('TOTAL_SUPPLY','总供给','OPENING_INVENTORY + LOCAL_PRODUCTION + EXTERNAL_INFLOW + IMPORTS + OTHER_SUPPLY',10),
 ('TOTAL_USE','总使用','FOOD_USE + FEED_USE + SEED_USE + PROCESSING_USE + LOSS + EXTERNAL_OUTFLOW + EXPORTS + OTHER_USE',20),
 ('CALCULATED_ENDING_INVENTORY','计算期末库存','TOTAL_SUPPLY - TOTAL_USE',30),
 ('ADOPTED_ENDING_INVENTORY','采用后账面期末库存','CALCULATED_ENDING_INVENTORY + APPROVED_ADJUSTMENT',40),
 ('INVENTORY_RECONCILIATION_DIFFERENCE','库存核对差额（调查期末库存－采用后账面期末库存）','SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY',50)
) expression(code,label,formula,sort_order)
WHERE formula_version.code = 'GRAIN_BALANCE' AND formula_version.version_no = 1;

CREATE TABLE supply.account_input_role (
    role_code varchar(80) PRIMARY KEY,
    group_code varchar(30) NOT NULL CHECK (group_code IN ('SUPPLY','USE','RECONCILIATION')),
    label varchar(160) NOT NULL UNIQUE,
    required boolean NOT NULL,
    sort_order integer NOT NULL UNIQUE
);
INSERT INTO supply.account_input_role(role_code, group_code, label, required, sort_order) VALUES
 ('OPENING_INVENTORY','SUPPLY','期初库存',true,10),
 ('LOCAL_PRODUCTION','SUPPLY','本地生产',true,20),
 ('EXTERNAL_INFLOW','SUPPLY','区域外流入',true,30),
 ('IMPORTS','SUPPLY','进口',true,40),
 ('OTHER_SUPPLY','SUPPLY','其他供给',true,50),
 ('FOOD_USE','USE','口粮消费',true,60),
 ('FEED_USE','USE','饲用消费',true,70),
 ('SEED_USE','USE','种用消费',true,80),
 ('PROCESSING_USE','USE','加工投入',true,90),
 ('LOSS','USE','损耗',true,100),
 ('EXTERNAL_OUTFLOW','USE','区域外流出',true,110),
 ('EXPORTS','USE','出口',true,120),
 ('OTHER_USE','USE','其他使用',true,130),
 ('SURVEYED_ENDING_INVENTORY','RECONCILIATION','调查期末库存',true,140);

CREATE TABLE supply.source_release (
    source_release_id uuid PRIMARY KEY,
    source_domain varchar(30) NOT NULL CHECK (source_domain IN ('PRODUCTION','MARKET','LOGISTICS','SUPPLY')),
    source_record_id varchar(120) NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 0),
    approval_state varchar(30) NOT NULL CHECK (approval_state IN ('APPROVED')),
    approved_at timestamptz NOT NULL,
    quality_state varchar(30) NOT NULL CHECK (quality_state IN ('PASSED','WARNING','BLOCKING')),
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    marketing_year varchar(20) NOT NULL CHECK (btrim(marketing_year) <> ''),
    immutable_digest varchar(128) NOT NULL CHECK (btrim(immutable_digest) <> ''),
    UNIQUE(source_domain, source_record_id, source_version)
);
CREATE TABLE supply.source_release_value (
    source_release_id uuid NOT NULL REFERENCES supply.source_release(source_release_id),
    role_code varchar(80) NOT NULL REFERENCES supply.account_input_role(role_code),
    value numeric(18,4) NOT NULL,
    unit_code varchar(40) NOT NULL,
    PRIMARY KEY(source_release_id, role_code)
);

CREATE TABLE supply.adoption_decision (
    adoption_decision_id uuid PRIMARY KEY,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    marketing_year varchar(20) NOT NULL,
    role_code varchar(80) NOT NULL REFERENCES supply.account_input_role(role_code),
    source_release_id uuid REFERENCES supply.source_release(source_release_id),
    adopted_value numeric(18,4) NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    decided_by varchar(120) NOT NULL CHECK (btrim(decided_by) <> ''),
    decided_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE(product_code, region_code, marketing_year, role_code)
);
CREATE TABLE supply.approved_adjustment (
    adjustment_id uuid PRIMARY KEY,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    marketing_year varchar(20) NOT NULL,
    value numeric(18,4) NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    decided_by varchar(120) NOT NULL CHECK (btrim(decided_by) <> ''),
    decided_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE(product_code, region_code, marketing_year)
);

CREATE TABLE supply.calculation_run (
    calculation_run_id uuid PRIMARY KEY,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    marketing_year varchar(20) NOT NULL,
    formula_version_id bigint NOT NULL REFERENCES supply.formula_version(formula_version_id),
    result_state varchar(30) NOT NULL CHECK (result_state IN ('TRIAL','FORMAL_CANDIDATE','FORMAL')),
    validation_codes text[] NOT NULL,
    total_supply numeric(18,4), total_use numeric(18,4),
    calculated_ending_inventory numeric(18,4), approved_adjustment numeric(18,4),
    adopted_ending_inventory numeric(18,4), surveyed_ending_inventory numeric(18,4),
    inventory_reconciliation_difference numeric(18,4), balanced boolean,
    created_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE INDEX calculation_run_query ON supply.calculation_run
    (product_code, region_code, marketing_year, created_at DESC);
CREATE TABLE supply.calculation_source_reference (
    calculation_run_id uuid NOT NULL REFERENCES supply.calculation_run(calculation_run_id) ON DELETE CASCADE,
    role_code varchar(80) NOT NULL REFERENCES supply.account_input_role(role_code),
    source_release_id uuid NOT NULL REFERENCES supply.source_release(source_release_id),
    source_record_id varchar(120) NOT NULL,
    source_version bigint NOT NULL,
    adopted_value numeric(18,4) NOT NULL,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    drill_down_route varchar(300) NOT NULL CHECK (btrim(drill_down_route) <> ''),
    PRIMARY KEY(calculation_run_id, role_code),
    UNIQUE(calculation_run_id, source_release_id, role_code)
);
CREATE TABLE supply.result_version (
    result_version_id uuid PRIMARY KEY,
    calculation_run_id uuid NOT NULL UNIQUE REFERENCES supply.calculation_run(calculation_run_id),
    version_no integer NOT NULL CHECK (version_no > 0),
    published_by varchar(120), published_at timestamptz,
    UNIQUE(calculation_run_id, version_no),
    CHECK ((published_by IS NULL) = (published_at IS NULL))
);

-- Database-driven page definitions for all confirmed products.
INSERT INTO platform.field_definition(code, name, value_type) VALUES
 ('LOG_COLLECTION_DATE','物流采集日期','DATE'), ('LOG_REPORTED_AT','物流填报时间','DATETIME'),
 ('LOG_PERIOD','物流监测期','TEXT'), ('LOG_ORIGIN','物流起运节点','TEXT'), ('LOG_DESTINATION','物流到达节点','TEXT'),
 ('LOG_TRANSPORT_MODE','物流运输方式','TEXT'), ('LOG_DIRECTION','物流流向类型','TEXT'),
 ('LOG_ROUTE_VOLUME','物流运量','DECIMAL'), ('LOG_FREIGHT_RATE','物流运价','DECIMAL'),
 ('LOG_TRANSIT_TIME','物流在途时间','DECIMAL'), ('LOG_STATUS','物流状态','TEXT'),
 ('LOG_SOURCE_ORGANIZATION','物流来源单位','TEXT'), ('LOG_REPORTER','物流填报人','TEXT'),
 ('SUP_GROUP','供需业务段','TEXT'), ('SUP_ITEM','供需账户项目','TEXT'),
 ('SUP_ADOPTED_VALUE','供需采用值','DECIMAL'), ('SUP_SOURCE_VALUE','供需来源值','DECIMAL'),
 ('SUP_REASON','供需采用调整理由','TEXT'), ('SUP_SOURCE_STATUS','供需来源状态','TEXT'),
 ('SUP_RESULT_STATE','供需结果状态','TEXT');

INSERT INTO platform.page_definition(product_code,business_domain,page_kind)
SELECT code,'LOGISTICS','MONITORING' FROM platform.product
UNION ALL SELECT code,'SUPPLY','ACCOUNT' FROM platform.product;
INSERT INTO platform.page_definition_field(product_code,business_domain,page_kind,field_code,sort_order)
SELECT product.code,'LOGISTICS','MONITORING',field.code,field.sort_order FROM platform.product CROSS JOIN (VALUES
 ('LOG_COLLECTION_DATE',10),('LOG_REPORTED_AT',20),('LOG_PERIOD',30),('LOG_ORIGIN',40),('LOG_DESTINATION',50),
 ('LOG_TRANSPORT_MODE',60),('LOG_DIRECTION',70),('LOG_ROUTE_VOLUME',80),('LOG_FREIGHT_RATE',90),
 ('LOG_TRANSIT_TIME',100),('LOG_SOURCE_ORGANIZATION',110),('LOG_REPORTER',120),('LOG_STATUS',130)) field(code,sort_order)
UNION ALL
SELECT product.code,'SUPPLY','ACCOUNT',field.code,field.sort_order FROM platform.product CROSS JOIN (VALUES
 ('SUP_GROUP',10),('SUP_ITEM',20),('SUP_SOURCE_VALUE',30),('SUP_ADOPTED_VALUE',40),
 ('SUP_REASON',50),('SUP_SOURCE_STATUS',60),('SUP_RESULT_STATE',70)) field(code,sort_order);
INSERT INTO platform.page_presentation(product_code,business_domain,page_kind,title)
SELECT code,'LOGISTICS','MONITORING',name || '物流监测' FROM platform.product
UNION ALL SELECT code,'SUPPLY','ACCOUNT',name || '供需账户' FROM platform.product;
INSERT INTO platform.page_breadcrumb(product_code,business_domain,page_kind,code,label,sort_order)
SELECT code,'LOGISTICS','MONITORING','LOGISTICS','物流监测',10 FROM platform.product
UNION ALL SELECT code,'LOGISTICS','MONITORING','MONITORING','物流记录',20 FROM platform.product
UNION ALL SELECT code,'SUPPLY','ACCOUNT','SUPPLY','供需分析',10 FROM platform.product
UNION ALL SELECT code,'SUPPLY','ACCOUNT','ACCOUNT','供需平衡',20 FROM platform.product;
INSERT INTO platform.page_filter_definition(product_code,business_domain,page_kind,code,label,control_type,placeholder,sort_order)
SELECT product.code,'LOGISTICS','MONITORING',f.code,f.label,f.type,f.placeholder,f.sort_order FROM platform.product CROSS JOIN (VALUES
 ('regionCode','地区','REGION_HIERARCHY','全部地区',10),('periodCode','监测期','SELECT','全部监测期',20),
 ('nodeTypeCode','节点类型','SELECT','全部节点类型',30),('transportModeCode','运输方式','SELECT','全部运输方式',40),
 ('status','状态','SELECT','全部状态',50)) f(code,label,type,placeholder,sort_order)
UNION ALL
SELECT product.code,'SUPPLY','ACCOUNT',f.code,f.label,f.type,f.placeholder,f.sort_order FROM platform.product CROSS JOIN (VALUES
 ('regionCode','地区','REGION_HIERARCHY','请选择地区',10),('marketingYear','营销年度','TEXT','请输入营销年度',20),
 ('resultState','结果状态','SELECT','全部状态',30)) f(code,label,type,placeholder,sort_order);
INSERT INTO platform.page_filter_option(product_code,business_domain,page_kind,filter_code,value,label,sort_order)
SELECT product.code,'LOGISTICS','MONITORING','nodeTypeCode',object_type.code,object_type.name,object_type.sort_order
FROM platform.product CROSS JOIN platform.object_type WHERE object_type.business_domain='LOGISTICS'
UNION ALL SELECT product.code,'LOGISTICS','MONITORING','transportModeCode',mode.code,mode.name,mode.sort_order
FROM platform.product CROSS JOIN platform.transport_mode mode
UNION ALL SELECT product.code,'LOGISTICS','MONITORING','status',status.code,status.label,status.sort_order
FROM platform.product CROSS JOIN (VALUES ('DRAFT','草稿',10),('PENDING_REVIEW','待审核',20),('APPROVED','已审核',30),('RETURNED','退回补充',40)) status(code,label,sort_order)
UNION ALL SELECT product.code,'SUPPLY','ACCOUNT','resultState',status.code,status.label,status.sort_order
FROM platform.product CROSS JOIN (VALUES ('TRIAL','试算',10),('FORMAL_CANDIDATE','待发布',20),('FORMAL','正式',30)) status(code,label,sort_order);
INSERT INTO platform.page_column_group(product_code,business_domain,page_kind,code,label,sort_order)
SELECT code,'LOGISTICS','MONITORING','ROUTE','物流流向与数量',10 FROM platform.product
UNION ALL SELECT code,'SUPPLY','ACCOUNT','LEDGER','供需账户',10 FROM platform.product;
INSERT INTO platform.page_column_group_field(product_code,business_domain,page_kind,group_code,field_code,sort_order,unit,description)
SELECT product.code,'LOGISTICS','MONITORING','ROUTE',field.code,field.sort_order,field.unit,field.description
FROM platform.product CROSS JOIN (VALUES
 ('LOG_COLLECTION_DATE',10,NULL,NULL),('LOG_REPORTED_AT',20,NULL,NULL),('LOG_PERIOD',30,NULL,NULL),
 ('LOG_ORIGIN',40,NULL,NULL),('LOG_DESTINATION',50,NULL,NULL),('LOG_TRANSPORT_MODE',60,NULL,'运输明细，不单独重复汇总'),
 ('LOG_DIRECTION',70,NULL,NULL),('LOG_ROUTE_VOLUME',80,'吨','唯一物流事件运量'),('LOG_FREIGHT_RATE',90,'元/吨',NULL),
 ('LOG_TRANSIT_TIME',100,'小时',NULL),('LOG_SOURCE_ORGANIZATION',110,NULL,NULL),('LOG_REPORTER',120,NULL,NULL),('LOG_STATUS',130,NULL,NULL)) field(code,sort_order,unit,description)
UNION ALL SELECT product.code,'SUPPLY','ACCOUNT','LEDGER',field.code,field.sort_order,field.unit,field.description
FROM platform.product CROSS JOIN (VALUES
 ('SUP_GROUP',10,NULL,NULL),('SUP_ITEM',20,NULL,NULL),('SUP_SOURCE_VALUE',30,'万吨','核定来源值'),
 ('SUP_ADOPTED_VALUE',40,'万吨','供需账户采用值'),('SUP_REASON',50,NULL,'采用值或调整必须填写理由'),
 ('SUP_SOURCE_STATUS',60,NULL,NULL),('SUP_RESULT_STATE',70,NULL,NULL)) field(code,sort_order,unit,description);
INSERT INTO platform.page_action(product_code,business_domain,page_kind,code,label,action_scope,sort_order)
SELECT product.code,'LOGISTICS','MONITORING',a.code,a.label,a.scope,a.sort_order FROM platform.product CROSS JOIN (VALUES
 ('NEW','新建物流记录','PAGE',10),('VIEW','查看','ROW',20),('SUBMIT','提交','ROW',30),
 ('APPROVE','审核通过','ROW',40),('RETURN','退回补充','ROW',50)) a(code,label,scope,sort_order)
UNION ALL SELECT product.code,'SUPPLY','ACCOUNT',a.code,a.label,a.scope,a.sort_order FROM platform.product CROSS JOIN (VALUES
 ('VIEW_SOURCE','查看来源','ROW',10),('RUN','重新计算','PAGE',20),('ADJUST','声明采用/调整','PAGE',30)) a(code,label,scope,sort_order);
INSERT INTO platform.page_pagination(product_code,business_domain,page_kind,default_page_size)
SELECT code,'LOGISTICS','MONITORING',20 FROM platform.product
UNION ALL SELECT code,'SUPPLY','ACCOUNT',20 FROM platform.product;
INSERT INTO platform.page_size_option(product_code,business_domain,page_kind,page_size,sort_order)
SELECT product.code,domain.domain,domain.kind,size.value,size.sort_order
FROM platform.product CROSS JOIN (VALUES ('LOGISTICS','MONITORING'),('SUPPLY','ACCOUNT')) domain(domain,kind)
CROSS JOIN (VALUES (20,10),(50,20),(100,30)) size(value,sort_order);

COMMENT ON COLUMN supply.calculation_run.inventory_reconciliation_difference IS
    '唯一符号口径：调查期末库存减采用后账面期末库存；正值表示调查库存更高。';
COMMENT ON TABLE supply.source_release IS
    'Immutable approved upstream version reference; no mutable upstream business row is copied as another truth.';
