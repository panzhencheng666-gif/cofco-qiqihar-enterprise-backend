CREATE TABLE workflow.work_item_status (
    code varchar(30) PRIMARY KEY,
    label varchar(80) NOT NULL UNIQUE,
    pending_scope boolean NOT NULL,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE workflow.workflow_node (
    node_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(60) NOT NULL UNIQUE,
    label varchar(100) NOT NULL
);

CREATE TABLE workflow.responsible_party (
    responsible_party_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    party_type varchar(30) NOT NULL CHECK (party_type IN ('USER', 'WORK_UNIT')),
    external_code varchar(80) NOT NULL,
    display_name varchar(120) NOT NULL,
    UNIQUE (party_type, external_code)
);

CREATE TABLE workflow.work_item (
    work_item_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_name varchar(160) NOT NULL,
    business_domain varchar(30) NOT NULL,
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    product_code varchar(40) REFERENCES platform.product(code),
    business_period_code varchar(40) NOT NULL REFERENCES platform.business_period(code),
    due_at timestamptz NOT NULL,
    workflow_node_id bigint NOT NULL REFERENCES workflow.workflow_node(node_id),
    status_code varchar(30) REFERENCES workflow.work_item_status(code),
    responsible_party_id bigint NOT NULL
        REFERENCES workflow.responsible_party(responsible_party_id),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT work_item_scope_state_check CHECK (
        (completed_at IS NULL AND status_code IS NOT NULL)
        OR (completed_at IS NOT NULL AND status_code IS NULL)
    )
);

CREATE INDEX work_item_pending_query
    ON workflow.work_item (status_code, business_domain, region_code, product_code, due_at, work_item_id)
    WHERE completed_at IS NULL;
CREATE INDEX work_item_completed_query
    ON workflow.work_item (completed_at DESC, work_item_id DESC)
    WHERE completed_at IS NOT NULL;

CREATE TABLE workflow.work_item_audit_trail (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_item_id bigint NOT NULL REFERENCES workflow.work_item(work_item_id) ON DELETE CASCADE,
    workflow_node_id bigint REFERENCES workflow.workflow_node(node_id),
    status_code varchar(30) REFERENCES workflow.work_item_status(code),
    responsible_party_id bigint REFERENCES workflow.responsible_party(responsible_party_id),
    occurred_at timestamptz NOT NULL,
    note varchar(500)
);

INSERT INTO workflow.work_item_status (code, label, pending_scope, sort_order) VALUES
    ('TO_FILL', '待填报', true, 10),
    ('TO_REVIEW', '待审核', true, 20),
    ('RETURNED', '退回补充', true, 30),
    ('EXCEPTION', '异常处理', true, 40);

INSERT INTO platform.field_definition (code, name, value_type) VALUES
    ('WORK_TASK_NAME', '任务', 'TEXT'),
    ('WORK_BUSINESS_DOMAIN', '业务域', 'TEXT'),
    ('WORK_REGION_NAME', '地区', 'TEXT'),
    ('WORK_PRODUCT_NAME', '产品', 'TEXT'),
    ('WORK_BUSINESS_PERIOD', '业务期间', 'TEXT'),
    ('WORK_DUE_AT', '截止时间', 'DATETIME'),
    ('WORKFLOW_NODE_LABEL', '流程节点', 'TEXT'),
    ('WORK_STATUS_LABEL', '状态', 'TEXT'),
    ('WORK_RESPONSIBLE_PARTY', '责任人', 'TEXT');

INSERT INTO platform.page_definition (product_code, business_domain, page_kind)
VALUES (NULL, 'WORKFLOW', 'WORK_ITEMS');

INSERT INTO platform.page_definition_field
    (product_code, business_domain, page_kind, field_code, sort_order)
VALUES
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_TASK_NAME', 10),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_BUSINESS_DOMAIN', 20),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_REGION_NAME', 30),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_PRODUCT_NAME', 40),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_BUSINESS_PERIOD', 50),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_DUE_AT', 60),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORKFLOW_NODE_LABEL', 70),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_STATUS_LABEL', 80),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_RESPONSIBLE_PARTY', 90);

SET CONSTRAINTS ALL DEFERRED;

INSERT INTO platform.page_presentation
    (product_code, business_domain, page_kind, title)
VALUES (NULL, 'WORKFLOW', 'WORK_ITEMS', '任务列表');

INSERT INTO platform.page_breadcrumb
    (product_code, business_domain, page_kind, code, label, sort_order)
VALUES
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORKFLOW', '我的工作', 10),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'WORK_ITEMS', '任务列表', 20);

INSERT INTO platform.page_filter_definition
    (product_code, business_domain, page_kind, code, label, control_type, placeholder, sort_order)
VALUES
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'status', '状态', 'SELECT', '全部', 10),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'domain', '业务域', 'SELECT', '全部业务域', 20),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'regionId', '地区', 'REGION_HIERARCHY', '全部地区', 30),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'productCode', '产品', 'SELECT', '全部产品', 40);

INSERT INTO platform.page_filter_option
    (product_code, business_domain, page_kind, filter_code, value, label, sort_order)
SELECT NULL, 'WORKFLOW', 'WORK_ITEMS', 'status', code, label, sort_order
FROM workflow.work_item_status
WHERE pending_scope
ORDER BY sort_order;

INSERT INTO platform.page_filter_option
    (product_code, business_domain, page_kind, filter_code, value, label, sort_order)
VALUES
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'domain', 'PRODUCTION', '产情监测', 10),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'domain', 'MARKET', '市场监测', 20),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'domain', 'LOGISTICS', '物流监测', 30),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'domain', 'SUPPLY', '供需分析', 40),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'domain', 'REPORTING', '报表中心', 50);

INSERT INTO platform.page_column_group
    (product_code, business_domain, page_kind, code, label, sort_order)
VALUES (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', '任务信息', 10);

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order)
VALUES
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_TASK_NAME', 10),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_BUSINESS_DOMAIN', 20),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_REGION_NAME', 30),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_PRODUCT_NAME', 40),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_BUSINESS_PERIOD', 50),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_DUE_AT', 60),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORKFLOW_NODE_LABEL', 70),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_STATUS_LABEL', 80),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 'TASK', 'WORK_RESPONSIBLE_PARTY', 90);

INSERT INTO platform.page_pagination
    (product_code, business_domain, page_kind, default_page_size)
VALUES (NULL, 'WORKFLOW', 'WORK_ITEMS', 20);

INSERT INTO platform.page_size_option
    (product_code, business_domain, page_kind, page_size, sort_order)
VALUES
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 20, 10),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 50, 20),
    (NULL, 'WORKFLOW', 'WORK_ITEMS', 100, 30);

COMMENT ON TABLE workflow.work_item IS
    'Production work items. This migration intentionally seeds no work item or audit record.';
