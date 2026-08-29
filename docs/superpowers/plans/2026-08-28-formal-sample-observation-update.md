# Existing Formal Sample Observation Update Implementation Plan

> **Evidence boundary:** This document is a preserved implementation-plan snapshot. Its unchecked steps record the original execution sequence and are not completion evidence; completion claims require the separately recorded test, migration-replay, build, runtime, and browser results.

> **For agentic workers:** Execute inline in this task, one red-green slice at a time. Do not dispatch subagents and do not commit because the task explicitly protects a shared dirty worktree.

**Goal:** Add an independent one-click official-observation path for existing formal production, market, and logistics samples while preserving all existing create/import paths.

**Architecture:** A new `formalsampleobservation` deep module owns eligibility, locking, authorization, idempotency, history, projection version, and the single public REST contract. Existing domain services expose narrow internal methods that validate a locked formal-sample identity and insert an already-official domain record; existing effective projections remain the downstream source of truth. Web reuses the metadata-driven production/market/logistics forms in an explicit observation mode and never calls submit/approve for that mode.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring JDBC transactions, PostgreSQL/Flyway, React 19, TypeScript, Vitest/Testing Library, Playwright, Vite/Node 24.

## Global Constraints

- Work only on `feature/20260823-sample-network-comparison` in Backend and Web; Frontend receives no source change unless a verified contract dependency appears.
- Preserve every existing new-sample, first-submission, and batch-import path.
- No draft/review/approval UI or extra confirmation in the observation path; one `保存并同步` call becomes official.
- No cloud, production, remote business database, reset, clean, checkout overwrite, commit, merge, push, or branch switch.
- All database tests use `qiqihar_enterprise_test`; runtime acceptance uses only the managed local 8090/63182/63200 services.
- Regional supply balance must continue to read only regional annual data.

---

### Task 1: Lock the REST and persistence contract

**Files:**
- Create: `src/test/java/com/cofco/qiqihar/graintrade/formalsampleobservation/interfaceadapter/FormalSampleObservationRestIntegrationTest.java`
- Create: `src/main/resources/db/migration/V151__create_formal_sample_observation_contract.sql`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationDomain.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationCommand.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationResult.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationRepository.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationService.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/infrastructure/JdbcFormalSampleObservationRepository.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/interfaceadapter/FormalSampleObservationController.java`

**Interfaces:**
- Consumes: `AccessControl.require(permission, regionCode)`, the current security principal, existing domain draft JSON shapes, and `registry.sample_point`.
- Produces: `GET /api/v1/formal-sample-observations/eligible-samples`, `POST /api/v1/formal-sample-observations/observations`, and `GET /api/v1/formal-sample-observations/history`.

- [ ] **Step 1: Write the first failing integration test**

Seed one approved, valid formal sample and assert that an authorized employee can list it but an out-of-scope employee cannot. Assert the public item contains `samplePointId`, `sampleName`, `regionCode`, `regionName`, coordinates, validity dates, `latestObservationId`, `latestObservedAt`, and `latestValues`, and contains no `status`, `allowedActions`, or internal workflow code.

- [ ] **Step 2: Observe red**

Run:

```bash
./scripts/mvn-jdk21.sh -Dtest=FormalSampleObservationRestIntegrationTest test
```

Expected: compilation or 404 failure because the contract does not exist.

- [ ] **Step 3: Add the minimal migration and read contract**

Create `platform.formal_sample_observation` with `observation_id`, `source_domain`, `source_record_id`, `sample_point_id`, `product_code`, `observed_at`, `official_saved_at`, `actor_subject_id`, `idempotency_key`, `request_sha256`, `projection_version`, and `response_json`. Add unique constraints for `(actor_subject_id, source_domain, idempotency_key)` and `(source_domain, source_record_id)`, check the three domains, add history indexes, set migration-owner ownership, and grant only the required runtime SELECT/INSERT privileges.

Implement immutable records whose key signatures are:

```java
public record FormalSampleObservationCommand(
    FormalSampleObservationDomain domain, UUID samplePointId, String productCode,
    OffsetDateTime observedAt, JsonNode payload) {}

public record FormalSampleObservationResult(
    UUID observationId, UUID samplePointId, FormalSampleObservationDomain domain,
    String productCode, OffsetDateTime observedAt, OffsetDateTime officialSavedAt,
    String projectionVersion, List<String> synchronizedModules, JsonNode values) {}
```

The JDBC eligibility query must filter approved/valid registry rows, effective dates, existing approved+confirmed domain/product associations, authorized regions, and optional region/year filters.

- [ ] **Step 4: Run the focused test green**

Run the command from Step 2; expected: eligibility cases pass.

### Task 2: Save production observations atomically

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/production/application/ProductionRecordService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/production/application/ProductionRecordRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/production/infrastructure/JdbcProductionRecordRepository.java`
- Modify: Task 1 service/repository/controller/test files

**Interfaces:**
- Consumes: locked `FormalSampleIdentity`, `ProductionDraft`, `observedAt`, and actor.
- Produces: `ProductionRecordView saveOfficialObservation(FormalSampleIdentity identity, OffsetDateTime observedAt, ProductionDraft payload)`.

- [ ] **Step 1: Add a failing production save test**

Through POST, save a changed production observation and assert: HTTP success; one APPROVED+CONFIRMED production row linked to the original sample; old row still exists; history returns both; repeating the same key and payload returns the same IDs/count; same key with changed payload returns 409; audit contains `FORMAL_SAMPLE_OBSERVATION_SAVED`.

- [ ] **Step 2: Observe red with the focused Maven test**

Expected: POST is unimplemented or no domain record is created.

- [ ] **Step 3: Implement the minimal official production port**

Overlay locked sample name/region/coordinates/contact onto the incoming metadata, force survey date/year/month from `observedAt`, run existing production applicability/value/coordinate validation, construct `draft(...).submit().approve()`, and insert it with the supplied `sample_point_id`. Do not require evidence or `BUSINESS_APPROVE`; require normal create permission in the locked sample region. Record one official-observation audit event.

Before domain write, the module obtains a transaction advisory lock for actor/domain/idempotency key and locks the sample row. If a receipt exists, compare SHA-256 and return its response or raise 409. After the write, insert the observation receipt before commit.

- [ ] **Step 4: Observe green**

Run the same focused test and confirm all production assertions pass.

### Task 3: Save market and logistics observations through the same contract

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/infrastructure/JdbcMarketMonitoringRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/logistics/application/LogisticsService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/logistics/application/LogisticsRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/logistics/infrastructure/JdbcLogisticsRepository.java`
- Modify: Task 1 module/test files

**Interfaces:**
- Produces equivalent `saveOfficialObservation(...)` internal ports for `MarketMonitoringDraft` and `LogisticsDraft`.

- [ ] **Step 1: Add one failing test per domain**

Assert each save creates a new APPROVED+CONFIRMED row linked to the selected sample, preserves old history, and replays idempotently. Assert a sample associated only with another product/domain is rejected.

- [ ] **Step 2: Observe red**

Run only `FormalSampleObservationRestIntegrationTest`; expected: market/logistics cases fail at dispatch.

- [ ] **Step 3: Implement market then logistics vertical slices**

Market overlays locked `MKT_SAMPLE_NAME`, `MKT_REGION`, coordinates, and contact, forces trade date/year/month from `observedAt`, runs current metadata-driven parsing/validation, creates `draft().submit().approve()`, and inserts with the fixed sample point. Logistics overlays `LOG_SAMPLE_NAME`, `LOG_REGION`, coordinates, and contact, forces survey year/month and collection date, validates current public fields, and inserts directly as APPROVED with the fixed `route_event.sample_point_id`.

- [ ] **Step 4: Observe green after each domain**

Run the focused integration class after market, then after logistics.

### Task 4: Make current projections use the latest observation

**Files:**
- Modify: `src/main/resources/db/migration/V151__create_formal_sample_observation_contract.sql`
- Modify or create focused tests under `src/test/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/` and `src/test/java/com/cofco/qiqihar/graintrade/reporting/interfaceadapter/`
- Modify focused logistics read SQL only if the existing query lacks an effective-record seam.

**Interfaces:**
- Consumes: `platform.formal_sample_observation.observed_at` and `official_saved_at`.
- Produces: current production, market, and logistics record selection ordered by observation business time, then saved time, then stable record ID.

- [ ] **Step 1: Add a failing cross-consumer test**

Save a later observation for a sample, then requery the existing overview, matching analysis, and report APIs. Assert all return the new worked-example value and history still includes the previous literal. Query the regional supply-balance API before/after and assert its regional-data version/result is unchanged.

- [ ] **Step 2: Observe red**

Expected: at least one consumer selects the prior record or duplicates both records.

- [ ] **Step 3: Update only the effective selection seam**

Replace production/market effective views in V151 so ordering uses `COALESCE(observation.observed_at, legacy business date)`, then `COALESCE(observation.official_saved_at, record.updated_at)`. Add an effective logistics view and join it only in current logistics analysis/report queries. Do not alter regional production or supply-balance SQL.

- [ ] **Step 4: Observe green**

Run the focused observation and consumer consistency tests.

### Task 5: Lock the Web repository contract

**Files:**
- Modify: `src/platform/api/realtimeBusinessRepository.ts`
- Modify: `src/platform/api/realtimeBusinessRepository.spec.ts`

**Interfaces:**
- Produces: `listEligibleFormalSamples`, `saveFormalSampleObservation`, and `listFormalSampleObservationHistory` with discriminated `FormalSampleObservationDomain` types.

- [ ] **Step 1: Add failing Vitest cases**

Assert exact GET query names, POST body, `Idempotency-Key` request header, parsed success fields, and API error propagation. Use known literal responses rather than reconstructing parser output.

- [ ] **Step 2: Observe red**

Run:

```bash
npm test -- --run src/platform/api/realtimeBusinessRepository.spec.ts
```

- [ ] **Step 3: Implement typed methods**

Add the three methods to `RealtimeBusinessRepository` and its factory. Reuse the current client header/options seam; do not call create/submit/approve inside these methods.

- [ ] **Step 4: Observe green**

Run the same Vitest file.

### Task 6: Add the explicit observation mode to all three business pages

**Files:**
- Modify: `src/business/EnterpriseBusinessApplication.tsx`
- Modify: `src/business/ProductionMonitoringWorkspace.tsx`
- Modify: `src/business/MarketMonitoringWorkspace.tsx`
- Modify: `src/business/production/ProductProductionCollectionWorkspace.tsx`
- Modify: `src/business/production/ProductProductionCollectionWorkspace.spec.tsx`
- Modify: `src/business/market/ProductMarketCollectionWorkspace.tsx`
- Modify: `src/business/market/ProductMarketCollectionWorkspace.spec.tsx`
- Modify: `src/business/market/LogisticsMonitoringWorkspace.tsx`
- Modify: `src/business/market/LogisticsMonitoringWorkspace.spec.tsx`
- Modify: `src/business/realtime/RealtimeBusinessOperationsPanel.tsx`
- Modify: `src/business/realtime/RealtimeBusinessOperationsPanel.spec.tsx`
- Modify: `src/business/realtime/RealtimeLogisticsOperationsPanel.tsx`
- Modify: `src/business/realtime/RealtimeLogisticsOperationsPanel.spec.tsx`
- Modify: `src/business/formal-enterprise.css`

**Interfaces:**
- Adds panel mode `observation` and a distinct `onUpdateSample(productCode, samplePointId?)` callback chain.

- [ ] **Step 1: Add failing page tests**

Assert “新建调查记录/导入” remains and “更新已有样本数据” appears separately. Opening observation mode lists eligible samples, selecting one displays a locked identity card and last observation, and the only primary mutation is “保存并同步”. Assert no draft/review/approve/return copy appears in this mode.

- [ ] **Step 2: Observe red with focused Vitest files**

Run the six touched component/repository spec files only.

- [ ] **Step 3: Implement observation mode**

Load eligible samples for the current domain/product/date. On selection, use `latestValues` to prefill editable business fields while rendering identity fields as outputs. Generate a UUID idempotency key when values first become dirty; reuse it on retry and replace it after any post-attempt edit. Save through `saveFormalSampleObservation`, render saved time/version/synchronized modules, reload eligible/history/list data, and bump the existing application refresh token.

Desktop layout is a two-column list/editor grid with a bounded editor; at `max-width: 900px`, change to one column and remove fixed/sticky positioning. Use existing dialog containment and never place the editor over the map.

- [ ] **Step 4: Observe green and run CSS/build checks**

Run focused tests, `npm run lint`, `npm run check:architecture`, and `npm run build` using Node 24.

### Task 7: Proportional regression, local publish, and real acceptance

**Files:**
- No new feature files; record evidence from commands and browser behavior.

- [ ] **Step 1: Backend gates**

Run the focused integration classes, migration replay, affected module tests, then the existing backend full suite with JDK 21. Run `git diff --check` on task-owned files.

- [ ] **Step 2: Web gates**

Run focused specs, then the existing full Vitest suite, lint/format/architecture/bundle budget/UI inventory and production build with Node 24. Do not regenerate unrelated baselines unless the verified build command requires it.

- [ ] **Step 3: Publish locally**

Use the repository's existing safe local-runtime publish scripts. Confirm source build and managed runtime artifact SHA-256 equality, then confirm 8090, 63182, and 63200 listeners and health. Keep caffeinate alive.

- [ ] **Step 4: Browser acceptance on 63182**

With the local system employee, verify both entry paths coexist; select a real formal sample; save one changed observation; refresh/requery history; verify the matching overview, analysis, and report; verify regional supply balance is unchanged; force a validation failure and retry; inspect desktop and narrow viewport for overflow or obstruction.

- [ ] **Step 5: Preserve a recoverable boundary**

Report exact task-owned files, tests/build/hash/health/browser evidence as separate claims, remaining gaps, and all pre-existing dirty files. Do not commit.
