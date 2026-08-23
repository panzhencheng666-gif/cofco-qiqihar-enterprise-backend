# 多层级年度样本网络与设计坐标治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留既有年度网络和九类真实业务图标的前提下，支持地市、区县、乡镇、行政村四级现有样本点，区分精确对应、区域关联和明确代表，并以村委会驻地/经审核代表位置治理 2332 个无年份设计样本点。

**Architecture:** `registry.sample_point` 继续保存稳定真实样本身份和其当前能确认到的行政区域；年度成员不再强制绑定行政村。新增年度设计关系表保存需要审核的精确对应与明确代表，区域关联按行政树实时计算。地图把行政村“设计覆盖标识”和正式精确坐标图钉分开，Web 管理页维护年度名单与关系，现有产情、市场、物流导入继续复用稳定样本身份并按业务期间生成记录。

**Tech Stack:** PostgreSQL 15/PostGIS/Flyway、Java 21/Spring Boot/JdbcClient/JUnit、React/TypeScript/Vite/Vitest/Playwright、Node 24。

## Global Constraints

- 仅修改三仓 `feature/20260823-sample-network-comparison`，不得直接修改或推送 `main`。
- 2026 是年度网络首个启用年度；程序支持任意后续年份，不写死 2026/2027。
- 设计样本点不带年份、不参与产量、价格、库存或供需指标计算。
- 区域关联不得计入村级精确覆盖率；区域级现有样本不得自动下沉到所有行政村。
- 不根据经纬度最近距离建立业务关系，不移动坐标迎合系统生成的展示分区。
- 未知真实坐标保持未知，不使用行政中心坐标冒充真实点。
- 产情、市场、物流导入仍使用稳定 `sample_point_id`；跨月新增业务记录，同月同领域同产品冲突走原记录修正。
- Backend 使用 JDK 21；Frontend/Web 使用 Node 24。
- 不重复执行已经完成的 2026 本地数据清理，不复制数据库，不进入云端或生产。
- 63182 是用户入口，8090 是权威业务服务，63200 是内部总揽地图渲染器。

---

### Task 1: 将年度成员与行政村解绑并建立受审核设计关系

**Files:**
- Create: `src/main/resources/db/migration/V134__support_multilevel_sample_network_relations.sql`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/infrastructure/AnnualSampleNetworkMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: V133 的 `registry.sample_network_year`、`registry.sample_network_membership`、`registry.village_design_sample_point`。
- Produces: 可为空的旧 `village_region_code` 兼容列，以及 `registry.sample_network_design_relation`。

- [ ] **Step 1: 写失败的迁移测试**

在 `AnnualSampleNetworkMigrationIntegrationTest` 增加断言：区县级样本可以成为年度成员而不填写村；旧村级成员被迁移为 `EXACT_VILLAGE`；区域关系必须引用年度成员和行政村；`EXPLICIT_REPRESENTATION` 必须有依据。

```java
assertThat(query("""
        SELECT is_nullable FROM information_schema.columns
        WHERE table_schema='registry' AND table_name='sample_network_membership'
          AND column_name='village_region_code'
        """)).isEqualTo("YES");
assertThat(query("""
        SELECT count(*) FROM registry.sample_network_design_relation
        WHERE relation_type='EXACT_VILLAGE' AND review_status='APPROVED'
        """)).isEqualTo("1");
```

- [ ] **Step 2: 运行迁移测试并确认失败**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -Dtest=AnnualSampleNetworkMigrationIntegrationTest test
```

Expected: FAIL，因为 V134 和关系表尚不存在。

- [ ] **Step 3: 编写 V134**

迁移执行以下不可逆、可重放到空测试库的结构变化：

```sql
DROP TRIGGER IF EXISTS sample_network_membership_village_guard
ON registry.sample_network_membership;
DROP FUNCTION IF EXISTS registry.guard_sample_network_membership_village();
ALTER TABLE registry.sample_network_membership
    ALTER COLUMN village_region_code DROP NOT NULL;

CREATE TABLE registry.sample_network_design_relation (
    network_year smallint NOT NULL,
    sample_point_id uuid NOT NULL,
    design_village_region_code varchar(12) NOT NULL
        REFERENCES platform.region(code) ON DELETE RESTRICT,
    relation_type varchar(30) NOT NULL,
    evidence_reference varchar(500),
    review_status varchar(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    created_by varchar(120) NOT NULL REFERENCES platform.security_user(subject_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by varchar(120) REFERENCES platform.security_user(subject_id),
    reviewed_at timestamptz,
    PRIMARY KEY(network_year,sample_point_id,design_village_region_code,relation_type),
    FOREIGN KEY(network_year,sample_point_id)
      REFERENCES registry.sample_network_membership(network_year,sample_point_id)
      ON DELETE RESTRICT,
    CHECK (relation_type IN ('EXACT_VILLAGE','EXPLICIT_REPRESENTATION')),
    CHECK (review_status IN ('PENDING_REVIEW','APPROVED','RETURNED')),
    CHECK (relation_type<>'EXPLICIT_REPRESENTATION'
           OR length(btrim(evidence_reference))>0)
);

INSERT INTO registry.sample_network_design_relation(
  network_year,sample_point_id,design_village_region_code,relation_type,
  evidence_reference,review_status,created_by,created_at,reviewed_by,reviewed_at)
SELECT membership.network_year,membership.sample_point_id,membership.village_region_code,
       'EXACT_VILLAGE','V133 annual membership migration',
       CASE WHEN network.status_code='PUBLISHED' THEN 'APPROVED'
            ELSE 'PENDING_REVIEW' END,
       membership.created_by,membership.created_at,
       CASE WHEN network.status_code='PUBLISHED' THEN network.reviewed_by END,
       CASE WHEN network.status_code='PUBLISHED' THEN network.reviewed_at END
FROM registry.sample_network_membership membership
JOIN registry.sample_network_year network USING(network_year)
WHERE membership.village_region_code IS NOT NULL;
```

再增加行政村层级触发器、查询索引、owner/grant/comment，并将旧列注释为只读兼容字段，新代码不再依赖它计算覆盖。

- [ ] **Step 4: 重跑迁移测试**

Run: Task 1 Step 2 command.

Expected: PASS；空库回放到 V134，区县/乡镇成员可无村级关系，非法关系被约束拒绝。

- [ ] **Step 5: 提交 Backend 迁移检查点**

```bash
git add src/main/resources/db/migration/V134__support_multilevel_sample_network_relations.sql \
  src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/infrastructure/AnnualSampleNetworkMigrationIntegrationTest.java
git commit -m "feat(sample-network): support multilevel annual members"
```

### Task 2: 返回分层真实样本和三类对照关系

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/AnnualSampleNetworkView.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/SampleNetworkComparisonView.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/AnnualSampleNetworkRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/AnnualSampleNetworkService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/infrastructure/JdbcAnnualSampleNetworkRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/interfaceadapter/AnnualSampleNetworkController.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/interfaceadapter/AnnualSampleNetworkRestIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的关系表和 `registry.sample_point.region_code`。
- Produces: `SampleNetworkComparisonView` 的 `designPoints`、`actualPoints`、`relations` 与分层统计。

- [ ] **Step 1: 扩展 REST 失败测试**

测试构造一个乡镇级、一个区县级、一个村级年度成员；只为村级点建立 `EXACT_VILLAGE`，为乡镇级点建立一条 `EXPLICIT_REPRESENTATION`。断言：

```java
.andExpect(jsonPath("$.data.actualLevelCounts.township").value(1))
.andExpect(jsonPath("$.data.actualLevelCounts.county").value(1))
.andExpect(jsonPath("$.data.actualLevelCounts.village").value(1))
.andExpect(jsonPath("$.data.exactCoveredDesignPointCount").value(1))
.andExpect(jsonPath("$.data.representedDesignPointCount").value(1))
.andExpect(jsonPath("$.data.relations[?(@.relationType=='REGIONAL_ASSOCIATION')]").exists())
.andExpect(jsonPath("$.data.relations[?(@.relationType=='EXACT_VILLAGE')]").exists());
```

- [ ] **Step 2: 运行 REST 测试并确认旧 DTO 失败**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -Dtest=AnnualSampleNetworkRestIntegrationTest test
```

Expected: FAIL，因为旧实现强制 `villageRegionCode` 且只返回扁平 `points`。

- [ ] **Step 3: 定义新响应模型**

`SampleNetworkComparisonView` 改为分别返回：

```java
public record SampleNetworkComparisonView(
    int networkYear,
    String networkStatus,
    int designPointCount,
    int activeSamplePointCount,
    int exactCoveredDesignPointCount,
    int representedDesignPointCount,
    int regionalAssociationDesignPointCount,
    int unrelatedDesignPointCount,
    LevelCounts actualLevelCounts,
    List<DesignPoint> designPoints,
    List<ActualPoint> actualPoints,
    List<Relation> relations) {}
```

`ActualPoint` 必须包含 `locatedRegionCode/Name/Level`、可空经纬度、`locationState`；`Relation.relationType` 只能是 `EXACT_VILLAGE`、`EXPLICIT_REPRESENTATION` 或计算得到的 `REGIONAL_ASSOCIATION`。

- [ ] **Step 4: 改造年度成员命令**

成员命令的村编码改为可选设计关系：

```java
public record MemberDecisionRequest(
    String designVillageRegionCode,
    String relationType,
    String evidenceReference,
    String statusCode,
    String sourceCode,
    String reason,
    long version) {}
```

无设计村时只更新年度成员；填写设计村时在同一事务写入待审核关系。发布年度网络时，把该年度未退回关系标记为 `APPROVED` 并记录独立审核人。下一年度候选生成只引用稳定样本身份，旧关系复制为 `PENDING_REVIEW`。

- [ ] **Step 5: 实现分层查询**

`JdbcAnnualSampleNetworkRepository` 分三次查询设计村、年度真实成员和显式关系。区域关联通过 `WITH RECURSIVE` 行政树计算；禁止把它写回关系表。统计使用不重叠集合：精确覆盖优先，其次明确代表，区域关联只作说明，剩余为未建立关系。

- [ ] **Step 6: 运行测试**

Run: Task 2 Step 2 command.

Expected: PASS；区县/乡镇样本可在网，村级精确覆盖和区域关联分别统计。

- [ ] **Step 7: 提交 Backend 契约检查点**

```bash
git add src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network \
  src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/interfaceadapter/AnnualSampleNetworkRestIntegrationTest.java
git commit -m "feat(sample-network): expose hierarchical comparison relations"
```

### Task 3: 固化跨月导入不重复创建样本身份

**Files:**
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/GovernedProductWorkbookImportIntegrationTest.java`
- Modify only if the new regression fails: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/GovernedDraftImportService.java`
- Modify only if the new regression fails: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ImportDraftPromotionService.java`

**Interfaces:**
- Consumes: 现有产情、市场、物流 XLSX 契约、稳定样本身份和幂等键。
- Produces: 同一点跨月产生不同业务记录、同月重放不重复、主档不被静默覆盖的回归证据。

- [ ] **Step 1: 写跨月与同月重放测试**

使用现有 workbook helper 构造同一地区、同一名称、同一联系方式的 2026-08 和 2026-09 行，分别以不同幂等键导入并完成现有转正流程。断言：

```java
assertThat(jdbc.sql("SELECT count(DISTINCT sample_point_id) FROM production.production_record")
        .query(Long.class).single()).isOne();
assertThat(jdbc.sql("SELECT count(DISTINCT survey_month) FROM production.production_record")
        .query(Long.class).single()).isEqualTo(2);
assertThat(jdbc.sql("SELECT count(*) FROM production.production_record")
        .query(Long.class).single()).isEqualTo(2);
```

再次使用同一幂等键上传 9 月文件，断言记录数仍为 2；使用新幂等键上传同一 9 月业务边界但不同值，断言进入冲突/修正状态而不是第三条正式当前记录。

- [ ] **Step 2: 运行回归测试**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn \
  -Dtest=GovernedProductWorkbookImportIntegrationTest test
```

Expected: PASS；若失败，只修正稳定身份解析或业务期间幂等边界，不改变模板业务字段。

- [ ] **Step 3: 提交导入回归检查点**

```bash
git add src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/GovernedProductWorkbookImportIntegrationTest.java
git add src/main/java/com/cofco/qiqihar/graintrade/importing/application/GovernedDraftImportService.java \
  src/main/java/com/cofco/qiqihar/graintrade/importing/application/ImportDraftPromotionService.java 2>/dev/null || true
git commit -m "test(import): preserve sample identity across periods"
```

提交前逐项确认只暂存实际发生变化的文件，不把未变化路径或其他工作带入提交。

### Task 4: 将乡镇设计覆盖徽标与精确坐标图钉分开

**Files:**
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/domain/overviewSamplePoint.ts`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/infrastructure/http/HttpOverviewSamplePointRepository.ts`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/infrastructure/http/HttpOverviewSamplePointRepository.spec.ts`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/presentation/sampleNetworkLayers.ts`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/presentation/sampleNetworkLayers.spec.ts`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/components/OverviewSamplePointPanel.tsx`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/components/OverviewSamplePointPanel.spec.tsx`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/components/TerrainReliefBoundaryMap.tsx`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/components/terrainReliefGeometry.ts`
- Modify: `cofco-qiqihar-enterprise-frontend/src/modules/overview/ui/components/terrainReliefGeometry.spec.ts`

**Interfaces:**
- Consumes: Task 2 的三列表响应。
- Produces: 乡镇全村覆盖徽标、选中村突出、可选精确位置、区域样本标识。

- [ ] **Step 1: 写呈现失败测试**

覆盖以下行为：乡镇层级为每个下属行政村生成一个 `DESIGN_COVERAGE_BADGE`；覆盖徽标使用 `reliefPolygonInteriorAnchor` 而非设计经纬度；点击村后同乡镇其他徽标仍存在但为 `muted`；只有开启精确位置时生成 `DESIGN_EXACT_LOCATION`；没有坐标的区县/乡镇真实点生成 `REGIONAL_ACTUAL_BADGE` 而不是图钉。

- [ ] **Step 2: 运行定向测试并确认失败**

```bash
PATH=/opt/homebrew/opt/node@24/bin:$PATH npm test -- \
  src/modules/overview/ui/presentation/sampleNetworkLayers.spec.ts \
  src/modules/overview/ui/components/terrainReliefGeometry.spec.ts \
  src/modules/overview/ui/components/OverviewSamplePointPanel.spec.tsx \
  src/modules/overview/infrastructure/http/HttpOverviewSamplePointRepository.spec.ts
```

Expected: FAIL，因为旧代码把设计经纬度直接绘制为唯一设计图钉。

- [ ] **Step 3: 扩展前端语义类型**

```ts
export type SampleNetworkLayerType =
  | "ANNUAL_ACTUAL"
  | "DESIGN_COVERAGE_BADGE"
  | "DESIGN_EXACT_LOCATION"
  | "REGIONAL_ACTUAL_BADGE";

export type SampleNetworkRelationType =
  | "EXACT_VILLAGE"
  | "EXPLICIT_REPRESENTATION"
  | "REGIONAL_ASSOCIATION";
```

覆盖徽标只接收行政村代码和地图内部 anchor；精确位置单独接收审核后的经纬度。

- [ ] **Step 4: 实现地图层级规则**

市级只显示区县统计，县级只显示乡镇统计；乡镇级把全部下属村的设计覆盖徽标附着到已有村名 label anchor；行政村选中后将当前徽标设为 `selected`，兄弟村设为 `muted`。区域级真实样本没有坐标时附着到其行政区域 label anchor，并显示“仅确认到区县/乡镇”。

- [ ] **Step 5: 修正右侧栏文案与模式**

按钮使用“只看现有”“只看设计”“网络覆盖对照”。设计模式显示坐标来源、审核状态、精确对应/明确代表/区域关联，不再显示产情类 0、市场类 0。展示分区统一提示“行政村展示分区（非权威边界）”。

- [ ] **Step 6: 运行 Frontend 定向测试**

Run: Task 4 Step 2 command.

Expected: PASS。

- [ ] **Step 7: 提交 Frontend 检查点**

```bash
git add src/modules/overview
git commit -m "feat(overview): render hierarchical sample coverage"
```

### Task 5: 在 63182 管理和分析页面显示分层口径

**Files:**
- Modify: `cofco-qiqihar-enterprise-web/src/platform/api/realtimeBusinessRepository.ts`
- Modify: `cofco-qiqihar-enterprise-web/src/platform/api/realtimeBusinessRepository.spec.ts`
- Modify: `cofco-qiqihar-enterprise-web/src/business/samplepoint/AnnualSampleNetworkPanel.tsx`
- Modify: `cofco-qiqihar-enterprise-web/src/business/samplepoint/AnnualSampleNetworkPanel.spec.tsx`
- Modify: `cofco-qiqihar-enterprise-web/src/business/analysis/SampleNetworkCoverageStrip.tsx`
- Modify: `cofco-qiqihar-enterprise-web/src/business/analysis/SampleNetworkCoverageStrip.spec.tsx`
- Modify: `cofco-qiqihar-enterprise-web/src/business/samplepoint/annual-sample-network.css`
- Modify: `cofco-qiqihar-enterprise-web/src/business/analysis/sample-network-coverage.css`

**Interfaces:**
- Consumes: Task 2 REST 契约。
- Produces: 任意年度名单交接、可选设计关系、四级样本统计和不虚高的覆盖指标。

- [ ] **Step 1: 写 Web 失败测试**

断言年度成员表显示“所在地层级/区域”“设计关系”；新增成员允许不填行政村；只有选择精确对应/明确代表时才要求设计村，明确代表必须填写依据；覆盖条分别显示“村级精确覆盖”“明确代表覆盖”“乡镇级样本”“区县级样本”，不再显示含糊的“已覆盖行政村”。

- [ ] **Step 2: 运行定向测试并确认失败**

```bash
PATH=/opt/homebrew/opt/node@24/bin:$PATH npm test -- \
  src/platform/api/realtimeBusinessRepository.spec.ts \
  src/business/samplepoint/AnnualSampleNetworkPanel.spec.tsx \
  src/business/analysis/SampleNetworkCoverageStrip.spec.tsx
```

Expected: FAIL，因为当前新增成员强制填写行政村且覆盖条使用旧口径。

- [ ] **Step 3: 更新 API 类型和成员表单**

`SampleNetworkMemberDecision` 改为：

```ts
export interface SampleNetworkMemberDecision {
  designVillageRegionCode?: string;
  relationType?: "EXACT_VILLAGE" | "EXPLICIT_REPRESENTATION";
  evidenceReference?: string;
  statusCode: SampleNetworkMembershipStatus;
  sourceCode: "CARRIED_FORWARD" | "NEW" | "MANUAL";
  reason: string;
  version: number;
}
```

年度选择器支持当前已知年份与下一年度，不把候选来源限制为 `currentYear + 1`；创建下一年度时默认引用最近的已发布较早年度。

- [ ] **Step 4: 更新覆盖信息条**

信息条同时显示设计村总数、年度现有样本总数、村级精确覆盖、明确代表覆盖、地市/区县/乡镇/村级样本数和区域关联数，并保留“不参与业务指标计算”说明。

- [ ] **Step 5: 运行 Web 定向测试**

Run: Task 5 Step 2 command.

Expected: PASS。

- [ ] **Step 6: 提交 Web 检查点**

```bash
git add src/platform/api/realtimeBusinessRepository.ts \
  src/platform/api/realtimeBusinessRepository.spec.ts \
  src/business/samplepoint src/business/analysis/SampleNetworkCoverageStrip.tsx \
  src/business/analysis/SampleNetworkCoverageStrip.spec.tsx \
  src/business/analysis/sample-network-coverage.css
git commit -m "feat(sample-network): manage hierarchical annual coverage"
```

### Task 6: 治理 2332 个设计坐标并完成本地发布验收

**Files:**
- Create: `scripts/audit-village-design-coordinates.sh`
- Create: `docs/operations/village-design-coordinate-governance.md`
- Create locally but never commit: `.local-runtime/evidence/village-design-coordinate-audit-20260823.csv`
- Modify only after authoritative evidence is acquired and reviewed: governed geography import input consumed by existing `platform.region_location` workflow.

**Interfaces:**
- Consumes: 2332 行政村代码、现有 `platform.region_location`、权威村委会驻地来源和 PostGIS。
- Produces: 2332 行盘点、来源哈希、审核状态、异常类型；只把证据充分的坐标提升为正式审核状态。

- [ ] **Step 1: 建立只读坐标盘点脚本**

脚本拒绝远程主机和非 `qiqihar_enterprise_dev`，输出且断言：行政村总数 2332、坐标行 2332、来源/修订/审核状态分组、重复坐标、越出齐齐哈尔地市范围、行政层级/名称不一致。空间检查使用权威地市/区县/乡镇范围；系统生成村级展示分区只输出提示，不作为坐标真伪判定。

- [ ] **Step 2: 执行权威来源核对**

记录来源页面/数据集、许可、取得日期、原始文件 SHA-256 和匹配算法版本。按行政区划代码与“乡镇＋行政村正式名称”匹配；名称变体、非行政地名、农场/管理区层级异常全部进入人工异常清单，不做最近距离自动匹配。

- [ ] **Step 3: 生成并复核坐标证据**

```bash
./scripts/audit-village-design-coordinates.sh \
  --database qiqihar_enterprise_dev \
  --output .local-runtime/evidence/village-design-coordinate-audit-20260823.csv
```

Expected: 2332 行均有唯一行政村代码；每行明确 `REVIEWED` 或具体待核验原因；没有被静默移动的坐标。

- [ ] **Step 4: 运行三仓门禁**

Backend:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn verify
```

Frontend/Web 分别执行仓库现有 Node 24 全量 verify 命令；必须包括测试、format、lint、架构检查、构建，Web 还包括 bundle budget 与 UI inventory。长命令无输出时读取进程和最新日志，不以静默等待代替检查。

- [ ] **Step 5: 发布受管本地运行副本**

只使用源码仓库构建产物调用既有受管发布脚本；不得从 63182 反向复制源码，不复制数据库。同步完成后逐文件核对 SHA-256，并确认 8090、63182、63200 均由受管服务启动且健康。

- [ ] **Step 6: 浏览器验收**

在 63182 验收：总揽监测乡镇全村设计覆盖、选中村突出与精确位置开关、四级现有样本和三类关系；产情分析、市场分析、供需平衡分别显示新覆盖口径；年度网络管理能沿用上一年度稳定身份、新增区域级样本且不复制业务数据。

- [ ] **Step 7: 最终 Git 边界核对**

三仓执行 `git diff --check`、`git status --short --branch`、`git log -5 --oneline --decorate`。确认无数据库、坐标原始私有文件、运行产物、日志或截图进入提交。记录三仓分支 SHA、受管产物摘要和验收结果；仍保持任务分支未合并 `main`，随后按“远端任务分支 → PR → 检查/复核 → main”流程处理。
