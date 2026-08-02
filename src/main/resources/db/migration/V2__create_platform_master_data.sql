CREATE TABLE platform.region (
    code varchar(12) PRIMARY KEY,
    name varchar(100) NOT NULL,
    parent_code varchar(12) REFERENCES platform.region(code),
    administrative_level varchar(20) NOT NULL
        CHECK (administrative_level IN ('PREFECTURE', 'COUNTY')),
    sort_order integer NOT NULL,
    UNIQUE (parent_code, name)
);

CREATE TABLE platform.product (
    code varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    sort_order integer NOT NULL UNIQUE
);

CREATE TABLE platform.cultivar (
    code varchar(60) PRIMARY KEY,
    name varchar(100) NOT NULL,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    sort_order integer NOT NULL,
    UNIQUE (product_code, name),
    UNIQUE (product_code, sort_order)
);

CREATE TABLE platform.object_type (
    code varchar(60) PRIMARY KEY,
    name varchar(100) NOT NULL,
    business_domain varchar(30) NOT NULL,
    sort_order integer NOT NULL,
    UNIQUE (business_domain, name),
    UNIQUE (business_domain, sort_order)
);

CREATE TABLE platform.product_object_type (
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    object_type_code varchar(60) NOT NULL REFERENCES platform.object_type(code),
    PRIMARY KEY (product_code, object_type_code)
);

CREATE INDEX product_object_type_by_object
    ON platform.product_object_type(object_type_code, product_code);

CREATE TABLE platform.field_definition (
    code varchar(60) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    value_type varchar(30) NOT NULL
        CHECK (value_type IN ('DECIMAL', 'INTEGER', 'TEXT', 'DATE', 'DATETIME'))
);

CREATE TABLE platform.page_definition (
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind)
);

CREATE TABLE platform.page_definition_field (
    product_code varchar(40) NOT NULL,
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    field_code varchar(60) NOT NULL REFERENCES platform.field_definition(code),
    sort_order integer NOT NULL,
    PRIMARY KEY (product_code, business_domain, page_kind, field_code),
    UNIQUE (product_code, business_domain, page_kind, sort_order),
    FOREIGN KEY (product_code, business_domain, page_kind)
        REFERENCES platform.page_definition(product_code, business_domain, page_kind)
        ON DELETE CASCADE
);

CREATE TABLE platform.business_period (
    code varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    sort_order integer NOT NULL UNIQUE,
    CHECK (ends_on >= starts_on)
);

CREATE TABLE platform.business_batch (
    code varchar(60) PRIMARY KEY,
    name varchar(100) NOT NULL,
    business_period_code varchar(40) NOT NULL REFERENCES platform.business_period(code),
    sort_order integer NOT NULL,
    UNIQUE (business_period_code, name),
    UNIQUE (business_period_code, sort_order)
);

CREATE TABLE platform.page_default_context (
    business_domain varchar(30) NOT NULL,
    page_kind varchar(40) NOT NULL,
    default_product_code varchar(40) REFERENCES platform.product(code),
    default_business_period_code varchar(40) REFERENCES platform.business_period(code),
    default_business_batch_code varchar(60) REFERENCES platform.business_batch(code),
    PRIMARY KEY (business_domain, page_kind)
);
