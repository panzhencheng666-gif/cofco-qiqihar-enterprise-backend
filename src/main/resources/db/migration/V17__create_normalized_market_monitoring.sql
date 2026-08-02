CREATE TABLE platform.market_fact_category (
    code varchar(30) PRIMARY KEY,
    label varchar(100) NOT NULL,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE platform.market_fact_definition (
    code varchar(60) PRIMARY KEY,
    category varchar(30) NOT NULL REFERENCES platform.market_fact_category(code),
    label varchar(100) NOT NULL,
    unit varchar(40),
    decimal_precision integer NOT NULL CHECK (decimal_precision BETWEEN 1 AND 18),
    decimal_scale integer NOT NULL CHECK (decimal_scale BETWEEN 0 AND decimal_precision)
);

CREATE TABLE platform.market_fact_applicability (
    fact_code varchar(60) NOT NULL REFERENCES platform.market_fact_definition(code) ON DELETE CASCADE,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    object_type_code varchar(60) NOT NULL REFERENCES platform.object_type(code),
    sort_order integer NOT NULL,
    PRIMARY KEY (fact_code, product_code, object_type_code),
    UNIQUE (product_code, object_type_code, sort_order)
);

CREATE TABLE market.market_record (
    record_id varchar(36) PRIMARY KEY,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    object_type_code varchar(60) NOT NULL,
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    trade_date date NOT NULL,
    reported_at timestamptz NOT NULL,
    purchase_base_price numeric(18, 4),
    sale_base_price numeric(18, 4),
    trade_direction varchar(10) NOT NULL CHECK (trade_direction IN ('PURCHASE', 'SALE')),
    carriage_board_amount numeric(18, 4) NOT NULL DEFAULT 0 CHECK (carriage_board_amount >= 0),
    freight_amount numeric(18, 4) NOT NULL DEFAULT 0 CHECK (freight_amount >= 0),
    packaging_form varchar(10) CHECK (packaging_form IN ('BULK', 'BAGGED')),
    actual_trade_price numeric(18, 4) GENERATED ALWAYS AS (
        round((CASE WHEN trade_direction = 'PURCHASE' THEN purchase_base_price ELSE sale_base_price END)
            + carriage_board_amount + freight_amount, 4)) STORED,
    status_code varchar(30) NOT NULL CHECK (status_code IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'RETURNED')),
    return_reason varchar(500),
    last_modified_by varchar(120) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (trade_date <= (reported_at AT TIME ZONE 'Asia/Shanghai')::date),
    CHECK ((trade_direction = 'PURCHASE' AND purchase_base_price IS NOT NULL)
        OR (trade_direction = 'SALE' AND sale_base_price IS NOT NULL)),
    CHECK ((status_code = 'RETURNED' AND return_reason IS NOT NULL)
        OR (status_code <> 'RETURNED' AND return_reason IS NULL)),
    FOREIGN KEY (product_code, object_type_code)
        REFERENCES platform.product_object_type(product_code, object_type_code)
);
CREATE INDEX market_record_list_idx ON market.market_record(product_code, trade_date DESC, record_id);

CREATE TABLE market.market_record_fact (
    record_id varchar(36) NOT NULL REFERENCES market.market_record(record_id) ON DELETE CASCADE,
    fact_code varchar(60) NOT NULL REFERENCES platform.market_fact_definition(code),
    value numeric(18, 4) NOT NULL CHECK (value >= 0),
    PRIMARY KEY (record_id, fact_code)
);

CREATE FUNCTION market.require_fact_applicability()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner market.market_record%ROWTYPE;
BEGIN
    SELECT * INTO owner FROM market.market_record WHERE record_id = NEW.record_id;
    IF NOT EXISTS (SELECT 1 FROM platform.market_fact_applicability applicability
        WHERE applicability.fact_code = NEW.fact_code
          AND applicability.product_code = owner.product_code
          AND applicability.object_type_code = owner.object_type_code) THEN
        RAISE EXCEPTION 'Market fact % is not applicable to record %', NEW.fact_code, NEW.record_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER market_fact_applicability BEFORE INSERT OR UPDATE ON market.market_record_fact
FOR EACH ROW EXECUTE FUNCTION market.require_fact_applicability();

INSERT INTO platform.market_fact_category(code, label, sort_order) VALUES
 ('QUALITY', '质量指标', 10), ('PURCHASE', '采购与成交', 20), ('SALES', '销售', 30),
 ('PROCESSING', '加工生产', 40), ('INVENTORY', '库存', 50);
INSERT INTO platform.market_fact_definition(code, category, label, unit, decimal_precision, decimal_scale) VALUES
 ('MOISTURE', 'QUALITY', '水分', '%', 18, 1), ('TEST_WEIGHT', 'QUALITY', '容重', '克/升', 18, 0),
 ('IMPURITY', 'QUALITY', '杂质', '%', 18, 1), ('IMPERFECT_GRAIN', 'QUALITY', '不完善粒', '%', 18, 1),
 ('MILDEW', 'QUALITY', '霉变', '%', 18, 1), ('PROTEIN', 'QUALITY', '蛋白', '%', 18, 1),
 ('OIL_YIELD', 'QUALITY', '出油率', '%', 18, 1), ('MILLING_YIELD', 'QUALITY', '出米率', '%', 18, 1),
 ('BROWN_RICE_YIELD', 'QUALITY', '出糙率', '%', 18, 1), ('PURCHASE_VOLUME', 'PURCHASE', '采购量', '吨', 18, 4),
 ('SALES_VOLUME', 'SALES', '销售量', '吨', 18, 4), ('PROCESSING_INPUT', 'PROCESSING', '加工投入量', '吨/日', 18, 4),
 ('MAIN_OUTPUT', 'PROCESSING', '主产品产出量', '吨/日', 18, 4), ('BYPRODUCT_OUTPUT', 'PROCESSING', '副产品产出量', '吨/日', 18, 4),
 ('PROCESSING_LOSS', 'PROCESSING', '加工损耗', '吨/日', 18, 4), ('OPENING_INVENTORY', 'INVENTORY', '期初库存', '吨', 18, 4),
 ('STOCK_INFLOW', 'INVENTORY', '入库量', '吨', 18, 4), ('STOCK_OUTFLOW', 'INVENTORY', '出库量', '吨', 18, 4),
 ('STORAGE_LOSS', 'INVENTORY', '保管损耗', '吨', 18, 4), ('ENDING_INVENTORY', 'INVENTORY', '期末库存', '吨', 18, 4);

INSERT INTO platform.market_fact_applicability(fact_code, product_code, object_type_code, sort_order)
SELECT facts.code, product.code, object_type.code, facts.sort_order
FROM (VALUES ('MOISTURE', 10), ('TEST_WEIGHT', 20), ('IMPURITY', 30), ('IMPERFECT_GRAIN', 40), ('MILDEW', 50),
             ('PURCHASE_VOLUME', 60), ('SALES_VOLUME', 70), ('OPENING_INVENTORY', 80), ('STOCK_INFLOW', 90),
             ('STOCK_OUTFLOW', 100), ('STORAGE_LOSS', 110), ('ENDING_INVENTORY', 120)) facts(code, sort_order)
CROSS JOIN platform.product product CROSS JOIN platform.object_type object_type
WHERE object_type.code IN ('TRADER', 'WHOLESALE_MARKET', 'RESERVE_ENTERPRISE')
  AND (facts.code NOT IN ('TEST_WEIGHT', 'MILDEW') OR product.code = 'CORN');
DELETE FROM platform.market_fact_applicability
WHERE product_code = 'RICE' AND object_type_code IN ('TRADER', 'WHOLESALE_MARKET', 'RESERVE_ENTERPRISE')
  AND fact_code = 'IMPERFECT_GRAIN';
INSERT INTO platform.market_fact_applicability(fact_code, product_code, object_type_code, sort_order)
SELECT fact.code, product.code, object_type.code, fact.sort_order
FROM (VALUES ('PROTEIN', 51), ('OIL_YIELD', 52)) fact(code, sort_order)
CROSS JOIN (VALUES ('SOYBEAN')) product(code)
CROSS JOIN (VALUES ('TRADER'), ('WHOLESALE_MARKET'), ('RESERVE_ENTERPRISE')) object_type(code);
INSERT INTO platform.market_fact_applicability(fact_code, product_code, object_type_code, sort_order)
SELECT fact.code, product.code, object_type.code, fact.sort_order
FROM (VALUES ('MILLING_YIELD', 51), ('BROWN_RICE_YIELD', 52)) fact(code, sort_order)
CROSS JOIN (VALUES ('RICE')) product(code)
CROSS JOIN (VALUES ('TRADER'), ('WHOLESALE_MARKET'), ('RESERVE_ENTERPRISE')) object_type(code);
INSERT INTO platform.market_fact_applicability(fact_code, product_code, object_type_code, sort_order)
SELECT facts.code, product.code, object_type.code, facts.sort_order
FROM (VALUES ('MOISTURE', 10), ('TEST_WEIGHT', 20), ('IMPURITY', 30), ('IMPERFECT_GRAIN', 40), ('MILDEW', 50),
             ('PROTEIN', 60), ('OIL_YIELD', 70), ('MILLING_YIELD', 80), ('BROWN_RICE_YIELD', 90),
             ('PURCHASE_VOLUME', 100), ('PROCESSING_INPUT', 110), ('MAIN_OUTPUT', 120), ('BYPRODUCT_OUTPUT', 130),
             ('PROCESSING_LOSS', 140), ('OPENING_INVENTORY', 150), ('STOCK_INFLOW', 160), ('STOCK_OUTFLOW', 170),
             ('STORAGE_LOSS', 180), ('ENDING_INVENTORY', 190)) facts(code, sort_order)
CROSS JOIN platform.product product CROSS JOIN platform.object_type object_type
WHERE object_type.code = 'DEEP_PROCESSOR'
  AND ((product.code = 'CORN' AND facts.code NOT IN ('PROTEIN', 'OIL_YIELD', 'MILLING_YIELD', 'BROWN_RICE_YIELD'))
    OR (product.code = 'SOYBEAN' AND facts.code NOT IN ('TEST_WEIGHT', 'MILDEW', 'MILLING_YIELD', 'BROWN_RICE_YIELD'))
    OR (product.code = 'RICE' AND facts.code NOT IN ('TEST_WEIGHT', 'MILDEW', 'PROTEIN', 'OIL_YIELD')));
INSERT INTO platform.market_fact_applicability(fact_code, product_code, object_type_code, sort_order)
SELECT facts.code, facts.product, facts.object_type, facts.sort_order FROM (VALUES
 ('MOISTURE','CORN','BREEDING_FACTORY',10), ('TEST_WEIGHT','CORN','BREEDING_FACTORY',20), ('IMPURITY','CORN','BREEDING_FACTORY',30), ('IMPERFECT_GRAIN','CORN','BREEDING_FACTORY',40), ('MILDEW','CORN','BREEDING_FACTORY',50), ('PURCHASE_VOLUME','CORN','BREEDING_FACTORY',60), ('ENDING_INVENTORY','CORN','BREEDING_FACTORY',70),
 ('MOISTURE','CORN','FEED_MILL',10), ('TEST_WEIGHT','CORN','FEED_MILL',20), ('IMPURITY','CORN','FEED_MILL',30), ('IMPERFECT_GRAIN','CORN','FEED_MILL',40), ('MILDEW','CORN','FEED_MILL',50), ('PURCHASE_VOLUME','CORN','FEED_MILL',60), ('PROCESSING_INPUT','CORN','FEED_MILL',70), ('ENDING_INVENTORY','CORN','FEED_MILL',80),
 ('MOISTURE','RICE','RICE_MILL',10), ('MILLING_YIELD','RICE','RICE_MILL',20), ('BROWN_RICE_YIELD','RICE','RICE_MILL',30), ('IMPURITY','RICE','RICE_MILL',40), ('PURCHASE_VOLUME','RICE','RICE_MILL',50), ('PROCESSING_INPUT','RICE','RICE_MILL',60), ('MAIN_OUTPUT','RICE','RICE_MILL',70), ('BYPRODUCT_OUTPUT','RICE','RICE_MILL',80), ('PROCESSING_LOSS','RICE','RICE_MILL',90), ('ENDING_INVENTORY','RICE','RICE_MILL',100)
) facts(code, product, object_type, sort_order);

INSERT INTO platform.field_definition(code, name, value_type) VALUES
 ('MKT_REGION','市场地区','TEXT'), ('MKT_OBJECT_TYPE','市场对象类型','TEXT'), ('MKT_TRADE_DATE','市场交易日期','DATE'),
 ('MKT_PURCHASE_BASE_PRICE','市场采购价','DECIMAL'), ('MKT_SALE_BASE_PRICE','市场销售价','DECIMAL'),
 ('MKT_ACTUAL_TRADE_PRICE','市场实际成交价','DECIMAL'), ('MKT_STATUS','市场填报状态','TEXT') ON CONFLICT (code) DO NOTHING;
INSERT INTO platform.page_definition(product_code, business_domain, page_kind)
SELECT code, 'MARKET', 'MONITORING' FROM platform.product;
INSERT INTO platform.page_definition_field(product_code, business_domain, page_kind, field_code, sort_order)
SELECT product.code, 'MARKET', 'MONITORING', field.code, field.sort_order FROM platform.product CROSS JOIN (VALUES
 ('MKT_REGION',10), ('MKT_OBJECT_TYPE',20), ('MKT_TRADE_DATE',30), ('MKT_PURCHASE_BASE_PRICE',40), ('MKT_SALE_BASE_PRICE',50), ('MKT_ACTUAL_TRADE_PRICE',60), ('MKT_STATUS',70)
) field(code, sort_order);
INSERT INTO platform.page_presentation(product_code, business_domain, page_kind, title)
SELECT code, 'MARKET', 'MONITORING', name || '市场采集' FROM platform.product;
INSERT INTO platform.page_breadcrumb(product_code, business_domain, page_kind, code, label, sort_order)
SELECT code, 'MARKET', 'MONITORING', 'MARKET', '市场监测', 10 FROM platform.product UNION ALL
SELECT code, 'MARKET', 'MONITORING', 'MONITORING', '市场采集', 20 FROM platform.product;
INSERT INTO platform.page_filter_definition(product_code,business_domain,page_kind,code,label,control_type,placeholder,sort_order)
SELECT product.code, 'MARKET', 'MONITORING', filter.code, filter.label, filter.type, filter.placeholder, filter.sort_order FROM platform.product CROSS JOIN (VALUES
 ('regionCode','地区','REGION_HIERARCHY','全部地区',10), ('objectTypeCode','对象类型','SELECT','全部适用对象',20), ('tradeDate','交易日期','DATE','请选择交易日期',30), ('status','状态','SELECT','全部状态',40)
) filter(code,label,type,placeholder,sort_order);
INSERT INTO platform.page_filter_option(product_code,business_domain,page_kind,filter_code,value,label,sort_order)
SELECT applicability.product_code, 'MARKET', 'MONITORING', 'objectTypeCode', type.code, type.name, type.sort_order FROM platform.product_object_type applicability JOIN platform.object_type type ON type.code=applicability.object_type_code WHERE type.business_domain='MARKET';
INSERT INTO platform.page_filter_option(product_code,business_domain,page_kind,filter_code,value,label,sort_order)
SELECT product.code, 'MARKET', 'MONITORING', 'status', x.code, x.label, x.sort_order FROM platform.product product CROSS JOIN (VALUES ('DRAFT','草稿',10),('PENDING_REVIEW','待审核',20),('APPROVED','已审核',30),('RETURNED','退回补充',40)) x(code,label,sort_order);
INSERT INTO platform.page_column_group(product_code,business_domain,page_kind,code,label,sort_order)
SELECT code, 'MARKET', 'MONITORING', 'MARKET', '市场监测信息', 10 FROM platform.product;
INSERT INTO platform.page_column_group_field(product_code,business_domain,page_kind,group_code,field_code,sort_order,unit)
SELECT product.code,'MARKET','MONITORING','MARKET',field.code,field.sort_order,field.unit FROM platform.product CROSS JOIN (VALUES
 ('MKT_REGION',10,NULL),('MKT_OBJECT_TYPE',20,NULL),('MKT_TRADE_DATE',30,NULL),('MKT_PURCHASE_BASE_PRICE',40,'元/吨'),('MKT_SALE_BASE_PRICE',50,'元/吨'),('MKT_ACTUAL_TRADE_PRICE',60,'元/吨'),('MKT_STATUS',70,NULL)
) field(code,sort_order,unit);
INSERT INTO platform.page_action(product_code,business_domain,page_kind,code,label,action_scope,sort_order)
SELECT product.code,'MARKET','MONITORING',x.code,x.label,x.scope,x.sort_order FROM platform.product CROSS JOIN (VALUES ('NEW','新建填报','PAGE',10),('VIEW','查看','ROW',20),('SUBMIT','提交','ROW',30),('APPROVE','审核','ROW',40),('RETURN','退回','ROW',50)) x(code,label,scope,sort_order);
INSERT INTO platform.page_pagination(product_code,business_domain,page_kind,default_page_size) SELECT code,'MARKET','MONITORING',20 FROM platform.product;
INSERT INTO platform.page_size_option(product_code,business_domain,page_kind,page_size,sort_order)
SELECT product.code,'MARKET','MONITORING',x.size,x.sort_order FROM platform.product CROSS JOIN (VALUES (20,10),(50,20),(100,30)) x(size,sort_order);

COMMENT ON TABLE market.market_record IS 'Normalized market monitoring header. V17 seeds master data only, never business records.';
