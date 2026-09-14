ALTER TABLE production.regional_public_source
  ADD COLUMN source_class varchar(30) NOT NULL DEFAULT 'OFFICIAL',
  ADD COLUMN reliability_weight numeric(5,4) NOT NULL DEFAULT 1.0000
    CHECK (reliability_weight > 0 AND reliability_weight <= 1);

UPDATE production.regional_public_source SET
  source_class = CASE
    WHEN source_id='qqhr-2025-report' THEN 'INDUSTRY_MEDIA'
    WHEN source_type='WEATHER' THEN 'PUBLIC_DATA_SERVICE'
    ELSE 'OFFICIAL'
  END,
  reliability_weight = CASE
    WHEN source_id='qqhr-2025-report' THEN 0.7500
    WHEN source_type='WEATHER' THEN 0.9000
    ELSE 1.0000
  END;

INSERT INTO production.regional_public_source(
  source_id,root_region_code,source_type,source_name,source_url,published_on,parser_key,
  evidence,last_status,source_class,reliability_weight)
VALUES
 ('hlbe-media-2025-harvest','150700','AGRICULTURE','内蒙古新闻网（呼伦贝尔新闻公众号）',
  'https://inews.nmgnews.com.cn/system/2025/09/23/030231730.shtml','2025-09-24','GENERIC_PAGE',
  '2025年农作物播种超2900万亩、粮食超2500万亩、大豆约1300万亩，预计粮食总产135亿斤以上',
  'BOOTSTRAP_VERIFIED','MEDIA_PUBLIC_ACCOUNT',0.7200),
 ('heihe-daily-2025-soy','231100','AGRICULTURE','黑河日报',
  'https://www.heihe.gov.cn/hhs/c100749/202502/c11_321021.shtml','2025-02-19','GENERIC_PAGE',
  '黑河大豆面积约占全省三分之一，2025年计划稳定在2000万亩左右，并实施玉米大豆单产提升行动',
  'BOOTSTRAP_VERIFIED','MAINSTREAM_MEDIA',0.8500),
 ('heihe-2026-spring','231100','AGRICULTURE','黑河市农业农村局',
  'https://www.heihe.gov.cn/hhs/c100749/202605/c11_353018.shtml','2026-05-11','GENERIC_PAGE',
  '2026年计划播种2042.7万亩；春耕资金68.7亿元，种子10.5万吨、化肥45.1万吨、柴油12万吨；计划涉农贷款294亿元',
  'BOOTSTRAP_VERIFIED','OFFICIAL',1.0000),
 ('qqhr-people-2026-processing','230200','AGRICULTURE','人民网黑龙江频道',
  'https://hlj.people.com.cn/n2/2026/0515/c220024-41581714.html','2026-05-15','GENERIC_PAGE',
  '2025年粮食总产254.2亿斤；粮食加工企业191家，设计加工能力1979万吨，产值超过250亿元',
  'BOOTSTRAP_VERIFIED','MAINSTREAM_MEDIA',0.8500),
 ('dxal-rural-account-2025','232700','AGRICULTURE','黑龙江省农业农村厅公众号',
  'https://nynct.hlj.gov.cn/nynct/c115380/202512/c00_31894113.shtml','2025-12-03','GENERIC_PAGE',
  '呼玛县主要农作物综合机械化率99.27%以上，绿色食品认证面积超过100万亩，大豆示范辐射5万亩',
  'BOOTSTRAP_VERIFIED','GOVERNMENT_MEDIA',0.9000),
 ('dxal-agri-funding-2024','232700','POLICY','大兴安岭地区农业农村局',
  'https://www.dxal.gov.cn/dxal/c100104/202501/c13_301402.shtml','2025-01-06','GENERIC_PAGE',
  '2024年争取涉农项目补贴补助15.32亿元，其中粮食生产相关补贴补助10.28亿元，已发放9.93亿元',
  'BOOTSTRAP_VERIFIED','OFFICIAL',1.0000)
ON CONFLICT (source_id) DO NOTHING;

INSERT INTO production.regional_public_crop_metric(
  source_id,data_year,product_code,planted_area_mu,yield_per_mu_kg,total_output_kg,evidence)
VALUES
 ('hlbe-media-2025-harvest',2025,'SOYBEAN',13000000,NULL,NULL,
  '呼伦贝尔新闻公众号报道：2025年大豆种植面积约1300万亩')
ON CONFLICT (source_id,data_year,product_code) DO UPDATE SET
  planted_area_mu=EXCLUDED.planted_area_mu,evidence=EXCLUDED.evidence,fetched_at=now();

UPDATE production.regional_public_source
SET next_refresh_at=NULL
WHERE source_id IN ('hlbe-media-2025-harvest','heihe-daily-2025-soy','heihe-2026-spring',
                    'qqhr-people-2026-processing','dxal-rural-account-2025','dxal-agri-funding-2024');
