INSERT INTO platform.region (code, name, parent_code, administrative_level, sort_order) VALUES
    ('230200', '齐齐哈尔市', NULL, 'PREFECTURE', 10),
    ('231100', '黑河市', NULL, 'PREFECTURE', 20),
    ('150700', '呼伦贝尔市', NULL, 'PREFECTURE', 30);

INSERT INTO platform.region (code, name, parent_code, administrative_level, sort_order) VALUES
    ('230202', '龙沙区', '230200', 'COUNTY', 1010),
    ('230203', '建华区', '230200', 'COUNTY', 1020),
    ('230204', '铁锋区', '230200', 'COUNTY', 1030),
    ('230205', '昂昂溪区', '230200', 'COUNTY', 1040),
    ('230206', '富拉尔基区', '230200', 'COUNTY', 1050),
    ('230207', '碾子山区', '230200', 'COUNTY', 1060),
    ('230208', '梅里斯达斡尔族区', '230200', 'COUNTY', 1070),
    ('230221', '龙江县', '230200', 'COUNTY', 1080),
    ('230223', '依安县', '230200', 'COUNTY', 1090),
    ('230224', '泰来县', '230200', 'COUNTY', 1100),
    ('230225', '甘南县', '230200', 'COUNTY', 1110),
    ('230227', '富裕县', '230200', 'COUNTY', 1120),
    ('230229', '克山县', '230200', 'COUNTY', 1130),
    ('230230', '克东县', '230200', 'COUNTY', 1140),
    ('230231', '拜泉县', '230200', 'COUNTY', 1150),
    ('230281', '讷河市', '230200', 'COUNTY', 1160),
    ('231102', '爱辉区', '231100', 'COUNTY', 2010),
    ('231123', '逊克县', '231100', 'COUNTY', 2020),
    ('231124', '孙吴县', '231100', 'COUNTY', 2030),
    ('231181', '北安市', '231100', 'COUNTY', 2040),
    ('231182', '五大连池市', '231100', 'COUNTY', 2050),
    ('231183', '嫩江市', '231100', 'COUNTY', 2060),
    ('150721', '阿荣旗', '150700', 'COUNTY', 3010),
    ('150722', '莫力达瓦达斡尔族自治旗', '150700', 'COUNTY', 3020),
    ('150723', '鄂伦春自治旗', '150700', 'COUNTY', 3030),
    ('150783', '扎兰屯市', '150700', 'COUNTY', 3040);

INSERT INTO platform.product (code, name, sort_order) VALUES
    ('CORN', '玉米', 10),
    ('SOYBEAN', '大豆', 20),
    ('RICE', '稻谷', 30);

INSERT INTO platform.cultivar (code, name, product_code, sort_order) VALUES
    ('HEINONG_84', '黑农84', 'SOYBEAN', 10),
    ('DONGSHENG_22', '东生22', 'SOYBEAN', 20);

INSERT INTO platform.object_type (code, name, business_domain, sort_order) VALUES
    ('FARMER', '农户', 'PRODUCTION', 10),
    ('VILLAGE_COMMITTEE', '村委会', 'PRODUCTION', 20),
    ('AGRICULTURAL_TECH_STATION', '农技站', 'PRODUCTION', 30),
    ('TRADER', '贸易商', 'MARKET', 110),
    ('DEEP_PROCESSOR', '深加工', 'MARKET', 120),
    ('WHOLESALE_MARKET', '批发市场', 'MARKET', 130),
    ('RESERVE_ENTERPRISE', '承储企业', 'MARKET', 140),
    ('RICE_MILL', '米厂', 'MARKET', 150),
    ('BREEDING_FACTORY', '养殖厂', 'MARKET', 160),
    ('FEED_MILL', '饲料厂', 'MARKET', 170);

INSERT INTO platform.product_object_type (product_code, object_type_code)
SELECT product.code, object_type.code
FROM platform.product product
CROSS JOIN platform.object_type object_type
WHERE object_type.code IN (
    'FARMER', 'VILLAGE_COMMITTEE', 'AGRICULTURAL_TECH_STATION',
    'TRADER', 'DEEP_PROCESSOR', 'WHOLESALE_MARKET', 'RESERVE_ENTERPRISE'
);

INSERT INTO platform.product_object_type (product_code, object_type_code) VALUES
    ('RICE', 'RICE_MILL'),
    ('CORN', 'BREEDING_FACTORY'),
    ('CORN', 'FEED_MILL');

INSERT INTO platform.field_definition (code, name, value_type) VALUES
    ('MOISTURE', '水分', 'DECIMAL'),
    ('MILLING_YIELD', '出米率', 'DECIMAL'),
    ('BROWN_RICE_YIELD', '出糙率', 'DECIMAL'),
    ('IMPURITY', '杂质', 'DECIMAL'),
    ('PROTEIN', '蛋白', 'DECIMAL'),
    ('OIL_YIELD', '出油率', 'DECIMAL'),
    ('IMPERFECT_GRAIN', '不完善粒', 'DECIMAL');

INSERT INTO platform.page_definition (product_code, business_domain, page_kind) VALUES
    ('RICE', 'MARKET', 'QUALITY'),
    ('SOYBEAN', 'MARKET', 'QUALITY');

INSERT INTO platform.page_definition_field (
    product_code, business_domain, page_kind, field_code, sort_order
) VALUES
    ('RICE', 'MARKET', 'QUALITY', 'MOISTURE', 10),
    ('RICE', 'MARKET', 'QUALITY', 'MILLING_YIELD', 20),
    ('RICE', 'MARKET', 'QUALITY', 'BROWN_RICE_YIELD', 30),
    ('RICE', 'MARKET', 'QUALITY', 'IMPURITY', 40),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'PROTEIN', 10),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'OIL_YIELD', 20),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'IMPERFECT_GRAIN', 30),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'MOISTURE', 40),
    ('SOYBEAN', 'MARKET', 'QUALITY', 'IMPURITY', 50);
