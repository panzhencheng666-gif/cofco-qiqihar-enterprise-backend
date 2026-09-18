# 运营态势与周期报告实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有总览监测和报表中心内实现库点、铁路、公开态势和周期报告，并消除等价状态引起的重复刷新。

**Architecture:** 后端增加只读运营设施目录、公开事件快照和审计聚合报告；前端通过现有端口/适配器模式按页签懒加载，并在现有 MapLibre 地图中更新独立 GeoJSON source。正式数据与公开参考数据分表、分来源、分新鲜度呈现。

**Tech Stack:** Java 21、Spring Boot、PostgreSQL/PostGIS、Flyway、Apache POI、React 19、TypeScript、Zod、MapLibre GL、Vitest。

## Global Constraints

- 不改变现有导航、行政区钻取、样本点、权限和正式数据生命周期。
- 不伪造库点关系、仓容、价格、坐标、用户活动或公开事件。
- 外部来源失败必须降级并保留最后成功快照。
- 所有修改完成后只安装到本地，不执行公网发布。
- 后端新迁移从 `V210` 开始，不能修改已发布迁移。

---

### Task 1: 运营设施目录数据库与 API

**Files:**
- Create: `backend:src/main/resources/db/migration/V210__operational_facility_catalogue.sql`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/application/OperationalFacilityCatalogue.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/JdbcOperationalFacilityCatalogue.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/OperationalFacilityController.java`
- Test: `backend:src/test/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/OperationalFacilityRestIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/v1/overview/operational-facilities?regionCode=&productCode=&asOf=`.
- Produces: `OperationalFacilityCatalogue.Result(List<StorageFacility>, List<RailwayFacility>, SourceStatus)`.
- Railway rows are projected from `overview.regional_railway_feature`; storage rows come only from governed facility tables.

- [ ] **Step 1: Write failing REST integration tests**

```java
mockMvc.perform(get("/api/v1/overview/operational-facilities")
        .param("regionCode", "230200").param("productCode", "CORN"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.storageFacilities[0].relationType").value("OWNED"))
    .andExpect(jsonPath("$.data.railwayFacilities[0].sourceUrl").isNotEmpty());
```

Cover invalid region, absent coordinates, historical lease cutoff, current price selection, public evidence, and `BUSINESS_READ` authorization.

- [ ] **Step 2: Run the focused backend test and verify 404/failure**

Run: `./mvnw -Dtest=OperationalFacilityRestIntegrationTest test`

- [ ] **Step 3: Add V210 governed tables and permissions**

Create `overview.storage_facility`, `overview.storage_facility_price`, and `overview.storage_facility_evidence` with version columns, evidence constraints, PostGIS coordinate checks, append-only price history, and runtime `SELECT` grants. Seed only records whose relationship and coordinates are supported by existing internal data plus retained public evidence; omit unverifiable attributes.

- [ ] **Step 4: Implement catalogue query and controller**

```java
public interface OperationalFacilityCatalogue {
    Result find(String regionCode, String productCode, LocalDate asOf);
}
```

Use `ST_Covers` for region ownership, return coordinate-less storage facilities in the list, and exclude them from map-ready projections through `mapEligible=false`.

- [ ] **Step 5: Run migration and focused test**

Run: `./mvnw -Dtest=OperationalFacilityRestIntegrationTest test`
Expected: all tests pass with no fixture record leaking into another region.

- [ ] **Step 6: Commit Task 1**

```bash
git add src/main/resources/db/migration/V210__operational_facility_catalogue.sql src/main/java/com/cofco/qiqihar/graintrade/overview src/test/java/com/cofco/qiqihar/graintrade/overview
git commit -m "Add governed operational facility catalogue"
```

### Task 2: 库点与铁路独立页签和地图图标

**Files:**
- Create: `frontend:src/modules/overview/domain/operationalFacilities.ts`
- Create: `frontend:src/modules/overview/application/ports/OperationalFacilityRepository.ts`
- Create: `frontend:src/modules/overview/infrastructure/http/HttpOperationalFacilityRepository.ts`
- Create: `frontend:src/modules/overview/ui/components/OperationalFacilityMapLayer.tsx`
- Create: `frontend:src/modules/overview/ui/components/StorageFacilityPanel.tsx`
- Create: `frontend:src/modules/overview/ui/components/RailwayFacilityPanel.tsx`
- Modify: `frontend:src/modules/overview/ui/components/OverviewDataModePanel.tsx`
- Modify: `frontend:src/modules/overview/ui/components/BoundaryMap.tsx`
- Modify: `frontend:src/modules/overview/ui/pages/OverviewPage.tsx`
- Modify: `frontend:src/app/dependencies.ts`
- Test: matching `*.spec.ts(x)` files beside each component/adapter.

**Interfaces:**
- Adds `OverviewDataMode` values `STORAGE_FACILITIES` and `RAILWAY_FACILITIES`.
- Consumes Task 1 endpoint.
- Produces separate `selectedStorageFacilityId` and `selectedRailwayFacilityId`; detail components do not share a generic business body.

- [ ] **Step 1: Write failing adapter and tab tests**

Assert Zod rejects unknown relation types, tabs emit the two new modes, and clicking factory/train markers opens the correct dedicated panel.

- [ ] **Step 2: Run focused Vitest tests and verify failure**

Run: `npm test -- --run src/modules/overview/infrastructure/http/HttpOperationalFacilityRepository.spec.ts src/modules/overview/ui/components/OverviewDataModePanel.spec.tsx src/modules/overview/ui/components/OperationalFacilityMapLayer.spec.tsx`

- [ ] **Step 3: Implement domain, port, adapter and lazy query hook**

```ts
export interface OperationalFacilityRepository {
  find(query: { regionCode?: string; productCode: string; asOf: string }): Promise<OperationalFacilityCatalogue>;
}
```

Memoize the scalar query key and retain the last confirmed result while a same-scope refresh is pending.

- [ ] **Step 4: Implement marker layer**

Use one MapLibre GeoJSON source for both facility modes. Factory icons encode the three relationship types; train icons encode station/halt/yard. Ignore entries where `mapEligible=false`.

- [ ] **Step 5: Implement dedicated detail panels and source links**

Storage panel fields: relationship, address, capacity, latest price, product, validity, evidence, updated time. Railway panel fields: kind, operator, service, reference, nearby lines, coordinates, location relation, snapshot time.

- [ ] **Step 6: Wire OverviewPage without changing existing modes**

Only load the facility endpoint while either facility tab is active. Preserve sample, regional, supply and annotation state transitions.

- [ ] **Step 7: Run focused tests, frontend build, and commit**

```bash
npm test -- --run src/modules/overview/infrastructure/http/HttpOperationalFacilityRepository.spec.ts src/modules/overview/ui/components/OverviewDataModePanel.spec.tsx src/modules/overview/ui/components/OperationalFacilityMapLayer.spec.tsx src/modules/overview/ui/pages/OverviewPage.spec.tsx
npm run build
git add src/modules/overview src/app/dependencies.ts
git commit -m "Add storage and railway overview layers"
```

### Task 3: 审计活动聚合与全系统 DOCX

**Files:**
- Create: `backend:src/main/resources/db/migration/V211__periodic_activity_report_exports.sql`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/reporting/application/ActivityReport.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/reporting/application/ActivityReportService.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/reporting/application/ActivityReportRepository.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/reporting/infrastructure/JdbcActivityReportRepository.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/reporting/infrastructure/ActivityReportDocument.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/reporting/interfaceadapter/ActivityReportController.java`
- Test: `backend:src/test/java/com/cofco/qiqihar/graintrade/reporting/interfaceadapter/ActivityReportRestIntegrationTest.java`
- Test: `backend:src/test/java/com/cofco/qiqihar/graintrade/reporting/infrastructure/ActivityReportDocumentTest.java`

**Interfaces:**
- Produces: `GET /api/v1/activity-reports/personal?days=7|30`.
- Produces: `GET /api/v1/activity-reports/system?days=7|30` for administrators/reviewers.
- Produces: `POST /api/v1/activity-reports/system/exports` and content download.

- [ ] **Step 1: Write failing permission and aggregation tests**

Insert immutable audit events for active, disabled, departed and unrelated users. Assert personal reports never include colleagues; system reports include only effective users; sample creates/deletes and imports are classified once.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -Dtest=ActivityReportRestIntegrationTest,ActivityReportDocumentTest test`

- [ ] **Step 3: Add export persistence migration**

Store report kind, period boundaries, event cutoff, generated by/at, filename, content type, SHA-256 and bytes. Add `ACTIVITY_REPORT_SYSTEM` permission to `SYSTEM_ADMIN` and `BUSINESS_REVIEWER`; personal access uses authenticated identity.

- [ ] **Step 4: Implement SQL aggregation**

Classify action codes with an explicit Java mapping into `CREATED`, `UPDATED`, `DELETED`, `IMPORTED`, `SUBMITTED`, `RETURNED`, `APPROVED`, `EXPORTED`, `ANNOTATED`, and `OTHER`. Query counts and per-domain/per-unit breakdown at one event cutoff.

- [ ] **Step 5: Generate DOCX and persist atomically**

Use Apache POI through a focused `ActivityReportDocument`. Include definitions, zero values, active-user scope, event cutoff and traceability footer. Persist only after successful generation.

- [ ] **Step 6: Run tests and commit**

```bash
./mvnw -Dtest=ActivityReportRestIntegrationTest,ActivityReportDocumentTest test
git add src/main/resources/db/migration/V211__periodic_activity_report_exports.sql src/main/java/com/cofco/qiqihar/graintrade/reporting src/test/java/com/cofco/qiqihar/graintrade/reporting
git commit -m "Add governed periodic activity reports"
```

### Task 4: 报表中心动画回顾与系统总结

**Files:**
- Create: `frontend:src/modules/reporting/domain/activityReport.ts`
- Create: `frontend:src/modules/reporting/application/ports/ActivityReportRepository.ts`
- Create: `frontend:src/modules/reporting/infrastructure/http/HttpActivityReportRepository.ts`
- Create: `frontend:src/modules/reporting/ui/components/ActivityReportStory.tsx`
- Create: `frontend:src/modules/reporting/ui/components/SystemActivityReport.tsx`
- Create: `frontend:src/modules/reporting/ui/components/activity-report.css`
- Modify: `frontend:src/modules/reporting/ui/pages/ReportingCenterPage.tsx`
- Modify: `frontend:src/app/dependencies.ts`
- Test: matching adapter/component/page specs.

**Interfaces:**
- Consumes Task 3 endpoints.
- `ActivityReportStory` accepts `report`, `autoPlay`, `onAutoPlayChange` and never fetches data itself.

- [ ] **Step 1: Write failing tests for 7/30 day switching, autoplay, reduced motion and authorization**

Use fake timers to prove one slide advance per interval and no advance under reduced motion. Verify system tab appears only after successful authorized response and a 403 does not expose summary content.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `npm test -- --run src/modules/reporting/infrastructure/http/HttpActivityReportRepository.spec.ts src/modules/reporting/ui/components/ActivityReportStory.spec.tsx src/modules/reporting/ui/pages/ReportingCenterPage.spec.tsx`

- [ ] **Step 3: Implement adapter and story component**

Slides are fixed to cover, contribution, sample network, workflow, quality and traceability. Add previous/next, pause/play, progress, keyboard buttons and `aria-live="polite"` only for manual changes.

- [ ] **Step 4: Implement system preview and DOCX download**

Display action/domain/unit summaries and call the existing `HttpClient.download` path for generated documents.

- [ ] **Step 5: Run tests, accessibility assertions, build and commit**

```bash
npm test -- --run src/modules/reporting
npm run build
git add src/modules/reporting src/app/dependencies.ts
git commit -m "Show animated personal and system activity reports"
```

### Task 5: 公开事件快照与态势监测

**Files:**
- Create: `backend:src/main/resources/db/migration/V212__public_operational_event_snapshot.sql`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/application/PublicOperationalEventService.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/NasaEonetClient.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/JdbcPublicOperationalEventRepository.java`
- Create: `backend:src/main/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/OperationalSituationController.java`
- Create: `frontend:src/modules/overview/domain/operationalSituation.ts`
- Create: `frontend:src/modules/overview/application/ports/OperationalSituationRepository.ts`
- Create: `frontend:src/modules/overview/infrastructure/http/HttpOperationalSituationRepository.ts`
- Create: `frontend:src/modules/overview/ui/components/OperationalSituationPanel.tsx`
- Create: `frontend:src/modules/overview/ui/components/OperationalSituationMap.tsx`
- Test: focused backend and frontend tests.

**Interfaces:**
- Produces: `GET /api/v1/overview/operational-situation?regionCode=&productCode=`.
- Source statuses: `READY | STALE | UNAVAILABLE` with `lastSuccessAt`, `sourceAsOf`, `sourceUrl`, and `message`.

- [ ] **Step 1: Write failing client, cache and degradation tests**

Mock EONET success, timeout, malformed GeoJSON and empty regional bbox. Prove a timeout returns the last successful snapshot as `STALE`, and no snapshot returns `UNAVAILABLE` without failing the whole endpoint.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -Dtest=PublicOperationalEventServiceTest,OperationalSituationRestIntegrationTest test`

Run: `npm test -- --run src/modules/overview/infrastructure/http/HttpOperationalSituationRepository.spec.ts src/modules/overview/ui/components/OperationalSituationPanel.spec.tsx src/modules/overview/ui/components/OperationalSituationMap.spec.tsx`

- [ ] **Step 3: Add V212 snapshot tables and implement EONET refresh**

Use EONET v3 GeoJSON with the four-platform-region bounding box, a bounded HTTP timeout, explicit user agent, category allowlist, source attribution and last-success persistence. Schedule refresh separately from request handling.

- [ ] **Step 4: Implement native layer catalogue UI**

Add `OPERATIONAL_SITUATION` mode with independent toggles for storage, railway, logistics and public risks. Reuse Task 1 facilities and real-coordinate logistics points; update GeoJSON sources without recreating MapLibre.

- [ ] **Step 5: Run tests/build and commit**

```bash
git add src/main/resources/db/migration/V212__public_operational_event_snapshot.sql src/main/java/com/cofco/qiqihar/graintrade/overview src/test/java/com/cofco/qiqihar/graintrade/overview
git commit -m "Add source-aware operational situation backend"
cd ../cofco-qiqihar-enterprise-frontend
git add src/modules/overview
git commit -m "Add source-aware operational situation layers"
```

### Task 6: 请求复用与重复刷新治理

**Files:**
- Modify: `frontend:src/modules/overview/ui/pages/OverviewPage.tsx`
- Modify: `frontend:src/modules/overview/ui/hooks/useOverviewRealtimeRefresh.ts`
- Modify: `frontend:src/modules/overview/infrastructure/http/OverviewRequestReuse.spec.ts`
- Modify: `frontend:src/shared/ui/list-workbench/useListPageController.ts`
- Modify only affected monitoring hooks where evidence shows duplicate requests.
- Test: overview realtime, request reuse, App navigation and affected page specs.

**Interfaces:**
- Produces stable scalar query keys and latest-request guards for every new query.
- Preserves existing mutation refresh semantics.

- [ ] **Step 1: Add request-count regression tests**

Cover overview entry, switching each new tab, returning to a cached tab, changing product/year, rapid region changes, hidden/visible reconnect and one realtime burst. Assert exact calls per affected repository.

- [ ] **Step 2: Run focused tests and capture failing counts**

- [ ] **Step 3: Apply the smallest fixes**

Memoize query objects, abort or version old requests, coalesce same-domain events, and remove duplicate mount/navigation effects proven by the tests. Do not introduce a new global state framework.

- [ ] **Step 4: Run focused performance tests and commit**

```bash
git add src/modules/overview src/shared/ui/list-workbench/useListPageController.ts src/app
git commit -m "Prevent duplicate page and overview refreshes"
```

### Task 7: 全量验证、本地安装与浏览器验收

**Files:**
- Modify only defects found by review or acceptance.

- [ ] **Step 1: Run backend tests with isolated UTF-8 PostgreSQL test database**

Run focused integration tests first, then the full backend suite using `QIQIHAR_TEST_DB_URL` and an explicit username.

- [ ] **Step 2: Run frontend quality gates once**

```bash
npm run format:check
npm run lint
npm run architecture
npm test
npm run build
```

- [ ] **Step 3: Run two-axis code review and fix blocking findings**

Review requirement fidelity and code quality separately; repeat focused tests after fixes.

- [ ] **Step 4: Fast-forward local runtime mirrors and install**

Run `./scripts/local-runtime.sh install`, then verify backend 8090, business 63182 and overview 63200 return HTTP 200.

- [ ] **Step 5: Real-browser acceptance**

Verify storage types/icons/details/sources, railway icons and distinct detail, personal 7/30-day story, admin system preview and DOCX download, role denial, situation toggles/source states, region drilldown, no fake coordinates, exact request counts, and zero browser warnings/errors.

- [ ] **Step 6: Confirm local-only boundary and clean worktrees**

Do not invoke any public deployment command. Record final commits, clean statuses, runtime hashes and acceptance evidence.
