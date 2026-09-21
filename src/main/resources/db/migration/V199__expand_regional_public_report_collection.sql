-- Collect facts rather than seeding guessed values. Public products stay independent of platform.product.
INSERT INTO production.regional_public_source(
 source_id,root_region_code,source_type,source_name,source_url,published_on,parser_key,
 evidence,last_status,source_class,reliability_weight)
VALUES
 ('qqhr-annual-agriculture','230200','AGRICULTURE','齐齐哈尔统计公报（公开转载）',
 'https://tjgb.hongheiku.com/xjtjgb/xj2020/74433.html','2026-06-21','ANNUAL_QQHR',
 '读取公报农业、农畜产品、乡村人口及农村收入指标，按各自统计期展示。','WAITING_FOR_SOURCE_SYNC','INDUSTRY_MEDIA',0.75),
 ('heihe-annual-agriculture','231100','AGRICULTURE','黑河市政府农业概况',
 'https://www.heihe.gov.cn/hhs/c102676/tt.shtml',NULL,'ANNUAL_HEIHE',
 '读取政府农业概况引用的年度统计公报，覆盖粮食、经济作物及畜牧渔业。','WAITING_FOR_SOURCE_SYNC','OFFICIAL',1),
 ('dxal-annual-agriculture','232700','AGRICULTURE','大兴安岭行署统计公报',
 'https://dxal.gov.cn/dxal/c100013/tydp.shtml',NULL,'ANNUAL_DXAL',
 '读取行署公开统计公报，包含小麦、经济作物、畜牧与产业指标。','WAITING_FOR_SOURCE_SYNC','OFFICIAL',1),
 ('hlbe-annual-agriculture','150700','AGRICULTURE','呼伦贝尔统计公报（官方PDF）',
 'https://www.hlbe.gov.cn/file_hlbe/27/202604/202604200941473182kj06p.pdf','2026-04-20','ANNUAL_HLBE',
 '官方公报附件，马铃薯采用折粮口径，不按鲜薯重量展示。','WAITING_FOR_SOURCE_SYNC','OFFICIAL',1),
 ('qqhr-processing-drc','230200','AGRICULTURE','黑龙江省发改委（黑龙江日报报道）',
 'https://drc.hlj.gov.cn/drc/c111429/202605/c00_31944370.shtml','2026-05-27','QQHR_PROCESSING',
 '加工企业、粮食与乳品产能、奶牛、冷藏容量及配送专线，均为报道时点的产业背景。','WAITING_FOR_SOURCE_SYNC','GOVERNMENT_MEDIA',0.9)
ON CONFLICT (source_id) DO NOTHING;

-- Historical correction: certification and mechanization in this source describe Huma County.
UPDATE production.regional_public_indicator
SET label=CASE WHEN label='绿色食品认证面积' THEN '呼玛县绿色食品认证面积'
               WHEN label='主要农作物机械化率' THEN '呼玛县主要农作物机械化率' ELSE label END,
    method=method || '；仅覆盖呼玛县，不代表大兴安岭全地区'
WHERE source_id='dxal-rural-account-2025';

UPDATE production.regional_public_indicator
SET method='254.2亿斤×5=1271万吨；1亿斤=5万吨'
WHERE indicator_id='qqhr-grain-output-2025';

-- The spring sowing plan was linked to an annual report that cannot substantiate that plan.
-- Preserve the rows for audit, but exclude them from the public projection until a matching source exists.
UPDATE production.regional_public_indicator SET method=method || '；待核对计划原始来源'
WHERE indicator_id IN ('dxal-crop-area-2025','dxal-grain-area-2025');

GRANT INSERT,UPDATE ON production.regional_public_indicator TO qiqihar_enterprise_runtime;

INSERT INTO production.regional_public_source(source_id,root_region_code,source_type,source_name,source_url,parser_key,evidence,last_status,source_class,reliability_weight)
VALUES
 ('qqhr-annual-2024','230200','AGRICULTURE','齐齐哈尔2024统计公报（公开转载）','https://tjgb.hongheiku.com/djs/60570.html','ANNUAL_QQHR','历史同口径资料用于趋势拟合与回测。','WAITING_FOR_SOURCE_SYNC','INDUSTRY_MEDIA',0.75),
 ('qqhr-annual-2023','230200','AGRICULTURE','齐齐哈尔2023统计公报（公开转载）','https://tjgb.hongheiku.com/djs/49036.html','ANNUAL_QQHR','历史同口径资料用于趋势拟合与回测。','WAITING_FOR_SOURCE_SYNC','INDUSTRY_MEDIA',0.75),
 ('hlbe-annual-2024','150700','AGRICULTURE','呼伦贝尔2024统计公报','https://www.hlbe.gov.cn/OpennessContent/show/505991.html','ANNUAL_HLBE','历史同口径资料用于趋势拟合。','WAITING_FOR_SOURCE_SYNC','OFFICIAL',1)
ON CONFLICT(source_id) DO NOTHING;

INSERT INTO production.regional_public_source(source_id,root_region_code,source_type,source_name,source_url,parser_key,evidence,last_status,source_class,reliability_weight)
SELECT 'search-'||code,code,'AGRICULTURE',name||'每日联网搜索','https://search.brave.com/','WEB_DISCOVERY',
 '独立记录每日互联网检索执行状态；不能用固定来源核验代替搜索。','SEARCH_NOT_CONFIGURED','PUBLIC_SEARCH',0.5
FROM (VALUES ('230200','齐齐哈尔'),('231100','黑河'),('150700','呼伦贝尔'),('232700','大兴安岭')) r(code,name)
ON CONFLICT(source_id) DO NOTHING;
