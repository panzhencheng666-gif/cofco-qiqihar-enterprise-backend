-- Every formal business domain exposes the same daily/weekly/monthly reporting cadence.
-- Report definitions remain server-owned metadata; the browser never invents report types.

INSERT INTO reporting.report_definition(
    code,
    name,
    business_domain,
    business_subtype,
    frequency_code,
    version_no,
    active,
    sort_order
)
VALUES
    ('PRODUCTION_WEEKLY', '产情周报', 'PRODUCTION', 'MONITORING', 'WEEKLY', 1, true, 11),
    ('PRODUCTION_MONTHLY', '产情月报', 'PRODUCTION', 'MONITORING', 'MONTHLY', 1, true, 12),
    ('MARKET_WEEKLY', '市场周报', 'MARKET', 'MONITORING', 'WEEKLY', 1, true, 21),
    ('MARKET_MONTHLY', '市场月报', 'MARKET', 'MONITORING', 'MONTHLY', 1, true, 22),
    ('LOGISTICS_DAILY', '物流日报', 'LOGISTICS', 'MONITORING', 'DAILY', 1, true, 29),
    ('LOGISTICS_MONTHLY', '物流月报', 'LOGISTICS', 'MONITORING', 'MONTHLY', 1, true, 31),
    ('SUPPLY_DAILY', '供需日报', 'SUPPLY', 'BALANCE', 'DAILY', 1, true, 38),
    ('SUPPLY_WEEKLY', '供需周报', 'SUPPLY', 'BALANCE', 'WEEKLY', 1, true, 39)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    business_domain = EXCLUDED.business_domain,
    business_subtype = EXCLUDED.business_subtype,
    frequency_code = EXCLUDED.frequency_code,
    version_no = EXCLUDED.version_no,
    active = true;

INSERT INTO reporting.report_definition_section(
    report_definition_id,
    section_code,
    title,
    sort_order
)
SELECT definition.report_definition_id,
       section.code,
       section.title,
       section.sort_order
FROM reporting.report_definition definition
CROSS JOIN (VALUES
    ('OVERVIEW', '总体概览', 10),
    ('APPROVED_DATA', '核定数据', 20),
    ('ANALYSIS', '分析说明', 30)
) section(code, title, sort_order)
WHERE definition.business_domain IN ('PRODUCTION', 'MARKET', 'LOGISTICS', 'SUPPLY')
ON CONFLICT (report_definition_id, section_code) DO UPDATE
SET title = EXCLUDED.title,
    sort_order = EXCLUDED.sort_order;

COMMENT ON TABLE reporting.report_definition IS
    'Formal report catalog. Production, market, logistics and supply each provide daily, weekly and monthly definitions.';
