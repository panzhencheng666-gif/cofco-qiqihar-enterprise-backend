CREATE TABLE platform.page_presentation (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    title varchar(120) NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_definition(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_breadcrumb (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    code varchar(60) NOT NULL,
    label varchar(100) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, code),
    UNIQUE (product_code, business_domain, page_kind, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_presentation(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_filter_definition (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    code varchar(60) NOT NULL,
    label varchar(100) NOT NULL,
    control_type varchar(30) NOT NULL
        CHECK (control_type IN ('TEXT', 'DATE', 'SELECT', 'REGION_HIERARCHY')),
    placeholder varchar(120) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, code),
    UNIQUE (product_code, business_domain, page_kind, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_presentation(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_filter_option (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    filter_code varchar(60) NOT NULL,
    value varchar(80) NOT NULL,
    label varchar(100) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, filter_code, value),
    UNIQUE (product_code, business_domain, page_kind, filter_code, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind, filter_code)
        REFERENCES platform.page_filter_definition(product_code, business_domain, page_kind, code)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_default_value (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    filter_code varchar(60) NOT NULL,
    value varchar(200) NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, filter_code),
    FOREIGN KEY (product_code, business_domain, page_kind, filter_code)
        REFERENCES platform.page_filter_definition(product_code, business_domain, page_kind, code)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_column_group (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    code varchar(60) NOT NULL,
    label varchar(100) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, code),
    UNIQUE (product_code, business_domain, page_kind, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_presentation(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_column_group_field (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    group_code varchar(60) NOT NULL,
    field_code varchar(60) NOT NULL,
    sort_order integer NOT NULL,
    unit varchar(40),
    description varchar(240),
    PRIMARY KEY (product_code, business_domain, page_kind, group_code, field_code),
    UNIQUE (product_code, business_domain, page_kind, group_code, sort_order),
    UNIQUE (product_code, business_domain, page_kind, field_code),
    FOREIGN KEY (product_code, business_domain, page_kind, group_code)
        REFERENCES platform.page_column_group(product_code, business_domain, page_kind, code)
        ON DELETE CASCADE,
    FOREIGN KEY (product_code, business_domain, page_kind, field_code)
        REFERENCES platform.page_definition_field(product_code, business_domain, page_kind, field_code)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_action (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    code varchar(60) NOT NULL,
    label varchar(100) NOT NULL,
    action_scope varchar(20) NOT NULL CHECK (action_scope IN ('PAGE', 'ROW')),
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, code),
    UNIQUE (product_code, business_domain, page_kind, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_presentation(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_pagination (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    default_page_size integer NOT NULL CHECK (default_page_size > 0),
    PRIMARY KEY (product_code, business_domain, page_kind),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_presentation(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.page_size_option (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    page_size integer NOT NULL CHECK (page_size > 0),
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, page_size),
    UNIQUE (product_code, business_domain, page_kind, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_pagination(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

INSERT INTO platform.page_presentation
    (product_code, business_domain, page_kind, title)
VALUES
    ('RICE', 'MARKET', 'QUALITY', '稻谷质量指标'),
    ('SOYBEAN', 'MARKET', 'QUALITY', '大豆质量指标');

INSERT INTO platform.page_breadcrumb
    (product_code, business_domain, page_kind, code, label, sort_order)
VALUES
    ('RICE', 'MARKET', 'QUALITY', 'MARKET', '市场监测', 10),
    ('RICE', 'MARKET', 'QUALITY', 'QUALITY', '稻谷质量指标', 20),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'MARKET', '市场监测', 10),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'QUALITY', '大豆质量指标', 20);

INSERT INTO platform.page_column_group
    (product_code, business_domain, page_kind, code, label, sort_order)
VALUES
    ('RICE', 'MARKET', 'QUALITY', 'QUALITY', '质量指标', 10),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'QUALITY', '质量指标', 10);

INSERT INTO platform.page_column_group_field
    (product_code, business_domain, page_kind, group_code, field_code, sort_order)
SELECT product_code, business_domain, page_kind, 'QUALITY', field_code, sort_order
FROM platform.page_definition_field
WHERE business_domain = 'MARKET' AND page_kind = 'QUALITY';

INSERT INTO platform.page_pagination
    (product_code, business_domain, page_kind, default_page_size)
VALUES
    ('RICE', 'MARKET', 'QUALITY', 20),
    ('SOYBEAN', 'MARKET', 'QUALITY', 20);

INSERT INTO platform.page_size_option
    (product_code, business_domain, page_kind, page_size, sort_order)
VALUES
    ('RICE', 'MARKET', 'QUALITY', 20, 10),
    ('RICE', 'MARKET', 'QUALITY', 50, 20),
    ('RICE', 'MARKET', 'QUALITY', 100, 30),
    ('SOYBEAN', 'MARKET', 'QUALITY', 20, 10),
    ('SOYBEAN', 'MARKET', 'QUALITY', 50, 20),
    ('SOYBEAN', 'MARKET', 'QUALITY', 100, 30);

ALTER TABLE platform.page_pagination
    ADD CONSTRAINT page_pagination_default_size_fk
    FOREIGN KEY (product_code, business_domain, page_kind, default_page_size)
    REFERENCES platform.page_size_option(product_code, business_domain, page_kind, page_size)
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION platform.require_page_pagination()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_product_code varchar(40);
    checked_business_domain varchar(30);
    checked_page_kind varchar(40);
BEGIN
    checked_product_code := COALESCE(NEW.product_code, OLD.product_code);
    checked_business_domain := COALESCE(NEW.business_domain, OLD.business_domain);
    checked_page_kind := COALESCE(NEW.page_kind, OLD.page_kind);

    IF EXISTS (
        SELECT 1
        FROM platform.page_presentation presentation
        WHERE presentation.product_code = checked_product_code
          AND presentation.business_domain = checked_business_domain
          AND presentation.page_kind = checked_page_kind
    ) AND NOT EXISTS (
        SELECT 1
        FROM platform.page_pagination pagination
        WHERE pagination.product_code = checked_product_code
          AND pagination.business_domain = checked_business_domain
          AND pagination.page_kind = checked_page_kind
    ) THEN
        RAISE EXCEPTION 'Every page presentation requires pagination configuration';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER page_presentation_requires_pagination
AFTER INSERT OR UPDATE ON platform.page_presentation
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_page_pagination();

CREATE CONSTRAINT TRIGGER page_pagination_cannot_be_removed
AFTER DELETE OR UPDATE ON platform.page_pagination
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION platform.require_page_pagination();

COMMENT ON TABLE platform.page_pagination IS
    'Task 3 platform interaction configuration; not business master data; not sourced from the golden screenshot.';
