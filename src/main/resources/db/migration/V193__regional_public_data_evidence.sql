CREATE TABLE production.regional_public_source (
  source_id varchar(80) PRIMARY KEY,
  root_region_code varchar(12) NOT NULL,
  source_type varchar(20) NOT NULL CHECK (source_type IN ('AGRICULTURE','WEATHER','POLICY')),
  source_name varchar(200) NOT NULL,
  source_url text NOT NULL,
  published_on date,
  parser_key varchar(40) NOT NULL,
  evidence text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  last_attempt_at timestamptz,
  last_success_at timestamptz,
  next_refresh_at timestamptz,
  last_status varchar(30) NOT NULL DEFAULT 'BOOTSTRAP',
  last_error text,
  last_content_hash char(64),
  last_excerpt text
);

CREATE TABLE production.regional_public_crop_metric (
  source_id varchar(80) NOT NULL REFERENCES production.regional_public_source(source_id),
  data_year integer NOT NULL CHECK (data_year BETWEEN 2001 AND 2100),
  product_code varchar(40) NOT NULL REFERENCES platform.product(code),
  planted_area_mu numeric(20,4),
  yield_per_mu_kg numeric(20,4),
  total_output_kg numeric(24,4),
  evidence text NOT NULL,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id,data_year,product_code)
);

CREATE TABLE production.regional_weather_snapshot (
  root_region_code varchar(12) PRIMARY KEY,
  observed_at timestamptz NOT NULL,
  mean_temperature_c numeric(10,2),
  precipitation_mm numeric(10,2),
  soil_moisture_percent numeric(10,2),
  risk varchar(80) NOT NULL,
  assessment text NOT NULL,
  source_id varchar(80) NOT NULL REFERENCES production.regional_public_source(source_id),
  fetched_at timestamptz NOT NULL
);

INSERT INTO production.regional_public_source(
  source_id,root_region_code,source_type,source_name,source_url,published_on,parser_key,evidence,last_status)
VALUES
 ('heihe-2025-report','231100','AGRICULTURE','黑河市人民政府','https://www.heihe.gov.cn/hhs/c102676/202206/c11_199590.shtml','2026-04-29','HEIHE_REPORT','2025年统计公报：大豆2026.4万亩/52.9亿斤，玉米713.9万亩/62.4亿斤，稻谷15.5万亩/1.4亿斤','BOOTSTRAP_VERIFIED'),
 ('dxal-2025-report','232700','AGRICULTURE','大兴安岭地区统计局','https://www.dxal.gov.cn/dxal/c100066/202606/c13_339001.shtml','2026-06-04','DXAL_REPORT','2025年统计公报：玉米0.4万公顷/2.3万吨，大豆15.7万公顷/21.2万吨','BOOTSTRAP_VERIFIED'),
 ('qqhr-2025-report','230200','AGRICULTURE','齐齐哈尔市统计公报公开转载','https://tjgb.hongheiku.com/xjtjgb/xj2020/74433.html',NULL,'GENERIC_PAGE','公开转载公报：2025年粮食播种面积3728.5万亩，总产254.2亿斤；玉米、大豆和水稻产量均有披露','BOOTSTRAP_REFERENCE'),
 ('hlbe-report-list','150700','AGRICULTURE','呼伦贝尔市人民政府统计公报','https://www.hlbe.gov.cn/OpennessContent/showList/2/98/page_1.html',NULL,'GENERIC_PAGE','呼伦贝尔市政府统计公报栏目，系统每日检查最新发布内容','BOOTSTRAP_REFERENCE'),
 ('weather-qqhr','230200','WEATHER','Open-Meteo 多模型天气','https://api.open-meteo.com/v1/forecast?latitude=47.35&longitude=123.92&current=temperature_2m,precipitation,soil_moisture_0_to_1cm&timezone=Asia%2FShanghai','2026-09-14','OPEN_METEO','齐齐哈尔市中心坐标的逐日天气模型；下级地区按所属地市气候基线修正','BOOTSTRAP'),
 ('weather-heihe','231100','WEATHER','Open-Meteo 多模型天气','https://api.open-meteo.com/v1/forecast?latitude=50.25&longitude=127.53&current=temperature_2m,precipitation,soil_moisture_0_to_1cm&timezone=Asia%2FShanghai','2026-09-14','OPEN_METEO','黑河市中心坐标的逐日天气模型；下级地区按所属地市气候基线修正','BOOTSTRAP'),
 ('weather-hlbe','150700','WEATHER','Open-Meteo 多模型天气','https://api.open-meteo.com/v1/forecast?latitude=49.21&longitude=119.77&current=temperature_2m,precipitation,soil_moisture_0_to_1cm&timezone=Asia%2FShanghai','2026-09-14','OPEN_METEO','呼伦贝尔市中心坐标的逐日天气模型；下级地区按所属地市气候基线修正','BOOTSTRAP'),
 ('weather-dxal','232700','WEATHER','Open-Meteo 多模型天气','https://api.open-meteo.com/v1/forecast?latitude=50.42&longitude=124.12&current=temperature_2m,precipitation,soil_moisture_0_to_1cm&timezone=Asia%2FShanghai','2026-09-14','OPEN_METEO','大兴安岭地区中心坐标的逐日天气模型；下级地区按所属地市气候基线修正','BOOTSTRAP'),
 ('moa-2026-support','*','POLICY','农业农村部计划财务司','https://jcs.moa.gov.cn/gzdt/202603/t20260325_6482635.htm','2026-03-25','GENERIC_PAGE','2026年强农惠农富农政策：东北玉米大豆生产者补贴、稻谷补贴、完全成本保险和种植收入保险','BOOTSTRAP_VERIFIED'),
 ('central-2026-rural','*','POLICY','中央一号文件','https://www.ccdi.gov.cn/toutun/202602/t20260203_473514_m.html','2026-02-03','GENERIC_PAGE','稳定粮油生产，巩固大豆产能，推进大面积单产提升和黑土地保护','BOOTSTRAP_VERIFIED');

INSERT INTO production.regional_public_crop_metric(
  source_id,data_year,product_code,planted_area_mu,yield_per_mu_kg,total_output_kg,evidence)
VALUES
 ('heihe-2025-report',2025,'CORN',7139000,437.0360,3120000000,'713.9万亩；62.4亿斤，换算为31.2亿公斤'),
 ('heihe-2025-report',2025,'SOYBEAN',20264000,130.5270,2645000000,'2026.4万亩；52.9亿斤，换算为26.45亿公斤'),
 ('heihe-2025-report',2025,'RICE',155000,451.6129,70000000,'15.5万亩；1.4亿斤，换算为0.7亿公斤'),
 ('dxal-2025-report',2025,'CORN',60000,383.3333,23000000,'0.4万公顷×15=6万亩；2.3万吨'),
 ('dxal-2025-report',2025,'SOYBEAN',2355000,90.0212,212000000,'15.7万公顷×15=235.5万亩；21.2万吨');

CREATE INDEX regional_public_source_due
  ON production.regional_public_source(active,next_refresh_at,source_type);
CREATE INDEX regional_public_crop_metric_lookup
  ON production.regional_public_crop_metric(data_year,product_code);
