CREATE TABLE platform.market_core_field_object_exclusion (
    product_code varchar(40) NOT NULL,
    object_type_code varchar(60) NOT NULL,
    field_code varchar(60) NOT NULL,
    PRIMARY KEY (product_code, object_type_code, field_code),
    FOREIGN KEY (product_code, object_type_code)
        REFERENCES platform.product_object_type(product_code, object_type_code),
    FOREIGN KEY (field_code)
        REFERENCES platform.market_core_field_definition(code)
);

INSERT INTO platform.market_core_field_object_exclusion(
    product_code, object_type_code, field_code)
SELECT applicability.product_code, applicability.object_type_code, 'MKT_SALE_BASE_PRICE'
FROM platform.product_object_type_applicability applicability
WHERE applicability.object_type_code IN ('DEEP_PROCESSOR', 'FEED_MILL', 'BREEDING_FACTORY');

ALTER TABLE platform.market_core_field_object_exclusion OWNER TO qiqihar_migration_owner;
GRANT SELECT ON TABLE platform.market_core_field_object_exclusion TO qiqihar_enterprise_runtime;

COMMENT ON TABLE platform.market_core_field_object_exclusion IS
    '对象类型不适用的市场核心采集字段；定义查询与保存校验共同使用。';
