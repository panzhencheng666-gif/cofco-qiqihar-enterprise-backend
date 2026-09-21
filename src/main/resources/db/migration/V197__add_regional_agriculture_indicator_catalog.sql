CREATE TABLE production.regional_public_indicator (
  indicator_id varchar(100) PRIMARY KEY,
  root_region_code varchar(12) NOT NULL,
  category varchar(30) NOT NULL,
  label varchar(100) NOT NULL,
  value numeric(24,4) NOT NULL,
  unit varchar(30) NOT NULL,
  data_year integer NOT NULL CHECK (data_year BETWEEN 2001 AND 2100),
  data_kind varchar(30) NOT NULL CHECK (data_kind IN ('OBSERVED','ESTIMATED','PLAN','CONTEXT')),
  method text NOT NULL,
  source_id varchar(80) NOT NULL REFERENCES production.regional_public_source(source_id),
  sort_order integer NOT NULL DEFAULT 100,
  fetched_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO production.regional_public_indicator(
  indicator_id,root_region_code,category,label,value,unit,data_year,data_kind,method,source_id,sort_order)
VALUES
 ('qqhr-grain-area-2025','230200','LAND','粮食播种面积',3728.5,'万亩',2025,'OBSERVED','统计公报原值','qqhr-2025-report',10),
 ('qqhr-grain-output-2025','230200','PRODUCTION','粮食总产量',1271.0,'万吨',2025,'OBSERVED','254.2亿斤×0.05换算为万吨','qqhr-2025-report',20),
 ('qqhr-processing-enterprises-2025','230200','PROCESSING','粮食加工企业',191,'家',2025,'OBSERVED','媒体公开数据','qqhr-people-2026-processing',30),
 ('qqhr-processing-capacity-2025','230200','PROCESSING','粮食设计加工能力',1979,'万吨',2025,'OBSERVED','媒体公开数据','qqhr-people-2026-processing',31),
 ('qqhr-processing-value-2025','230200','PROCESSING','粮食加工产值',250,'亿元',2025,'CONTEXT','公开报道为超过250亿元，按下限记录','qqhr-people-2026-processing',32),
 ('heihe-seeding-plan-2026','231100','LAND','计划播种面积',2042.7,'万亩',2026,'PLAN','市级农情调度计划值','heihe-2026-spring',10),
 ('heihe-soy-share-province','231100','PRODUCTION','大豆面积占全省比重',33.33,'%',2025,'CONTEXT','公开报道约占全省三分之一','heihe-daily-2025-soy',20),
 ('heihe-agri-credit-2026','231100','FINANCE','计划涉农贷款',294,'亿元',2026,'PLAN','公开报道的金融机构计划投放额','heihe-2026-spring',40),
 ('heihe-spring-fund-2026','231100','INPUT','春耕资金需求',68.7,'亿元',2026,'PLAN','农业农村局春耕调度值','heihe-2026-spring',41),
 ('heihe-seed-demand-2026','231100','INPUT','种子需求量',10.5,'万吨',2026,'PLAN','农业农村局春耕调度值','heihe-2026-spring',42),
 ('heihe-fertilizer-demand-2026','231100','INPUT','化肥需求量',45.1,'万吨',2026,'PLAN','农业农村局春耕调度值','heihe-2026-spring',43),
 ('heihe-diesel-demand-2026','231100','INPUT','柴油需求量',12,'万吨',2026,'PLAN','农业农村局春耕调度值','heihe-2026-spring',44),
 ('heihe-machinery-2026','231100','TECHNOLOGY','完成检修农机',23.5,'万台',2026,'OBSERVED','农业农村局春耕调度值','heihe-2026-spring',45),
 ('hlbe-crop-area-2025','150700','LAND','农作物播种面积',2900,'万亩',2025,'CONTEXT','公开报道为超过2900万亩，按下限记录','hlbe-media-2025-harvest',10),
 ('hlbe-grain-area-2025','150700','LAND','粮食作物播种面积',2500,'万亩',2025,'CONTEXT','公开报道为突破2500万亩，按下限记录','hlbe-media-2025-harvest',11),
 ('hlbe-standard-farmland-2025','150700','INFRASTRUCTURE','累计高标准农田',830.59,'万亩',2025,'OBSERVED','公开报道累计建设值','hlbe-media-2025-harvest',30),
 ('hlbe-mechanization-2025','150700','TECHNOLOGY','综合机械化率',95,'%',2025,'CONTEXT','公开报道为超过95%，按下限记录','hlbe-media-2025-harvest',40),
 ('hlbe-protective-tillage-2025','150700','TECHNOLOGY','保护性耕作面积',1210,'万亩',2025,'OBSERVED','公开报道年度实施面积','hlbe-media-2025-harvest',41),
 ('hlbe-machinery-subsidy-2025','150700','TECHNOLOGY','补贴农牧业机具',9437,'台套',2025,'OBSERVED','媒体公众号公开数据','hlbe-media-2025-harvest',42),
 ('hlbe-yield-demo-2025','150700','TECHNOLOGY','单产提升示范区',237.02,'万亩',2025,'OBSERVED','媒体公众号公开数据','hlbe-media-2025-harvest',43),
 ('dxal-crop-area-2025','232700','LAND','农作物播种面积',269.79,'万亩',2025,'PLAN','地区备春耕公开计划值','dxal-2025-report',10),
 ('dxal-grain-area-2025','232700','LAND','粮食计划播种面积',266.36,'万亩',2025,'PLAN','地区备春耕公开计划值','dxal-2025-report',11),
 ('dxal-mechanization-2025','232700','TECHNOLOGY','主要农作物机械化率',99.27,'%',2025,'OBSERVED','省农业农村厅公众号公开值','dxal-rural-account-2025',30),
 ('dxal-green-certified-2025','232700','BRAND','绿色食品认证面积',100,'万亩',2025,'CONTEXT','公开值为超过100万亩，按下限记录','dxal-rural-account-2025',40),
 ('dxal-subsidy-2024','232700','FINANCE','粮食生产补贴补助',10.28,'亿元',2024,'OBSERVED','地区农业农村局公开值','dxal-agri-funding-2024',41),
 ('dxal-subsidy-paid-2024','232700','FINANCE','已发放补贴补助',9.93,'亿元',2024,'OBSERVED','地区农业农村局公开值','dxal-agri-funding-2024',42)
ON CONFLICT (indicator_id) DO NOTHING;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_app_runtime') THEN
    GRANT SELECT ON production.regional_public_indicator TO qiqihar_app_runtime;
  END IF;
END $$;
