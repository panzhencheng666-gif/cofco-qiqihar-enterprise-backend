CREATE TABLE platform.monitoring_scope (
    code varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL UNIQUE,
    purpose text NOT NULL,
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE platform.monitoring_scope_region (
    scope_code varchar(40) NOT NULL REFERENCES platform.monitoring_scope(code),
    region_code varchar(12) NOT NULL REFERENCES platform.region(code),
    included boolean NOT NULL,
    exclusion_reason text,
    PRIMARY KEY (scope_code, region_code),
    CHECK (included OR exclusion_reason IS NOT NULL)
);

CREATE INDEX monitoring_scope_region_included_idx
    ON platform.monitoring_scope_region(scope_code, included, region_code);

INSERT INTO platform.monitoring_scope(code, name, purpose)
VALUES (
    'FORMAL_BUSINESS',
    '正式粮食商情监测范围',
    '统一约束产情、市场、物流、供需、报表与总揽统计口径'
);

INSERT INTO platform.monitoring_scope_region(scope_code, region_code, included)
SELECT 'FORMAL_BUSINESS', code, true
FROM platform.region;

UPDATE platform.monitoring_scope_region
SET included = false,
    exclusion_reason = '按正式业务口径，齐齐哈尔市仅统计梅里斯达斡尔族区及九县（市）'
WHERE scope_code = 'FORMAL_BUSINESS'
  AND region_code IN (
      '230202',
      '230203',
      '230204',
      '230205',
      '230206',
      '230207'
  );

COMMENT ON TABLE platform.monitoring_scope IS
    '跨业务域共享的正式统计范围定义；不得在各业务模块分别维护排除名单。';

COMMENT ON TABLE platform.monitoring_scope_region IS
    '行政区主数据与统计范围分离；excluded 行仍保留主数据及可审计原因。';
