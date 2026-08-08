# Market Submission Evidence Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a complete MARKET submission contract with a concrete reported-object name, distinct reporter/customer contacts, required coordinates, one-to-five private photos, and atomic idempotent CSV/XLSX imports.

**Architecture:** MARKET keeps its metadata-driven core-field model and consumes a narrow named evidence application interface. Domain-specific import parsing stays in the importing module while reusing the existing bounded CSV/XLSX parsers and durable import repository. Creation and imports use real PostgreSQL transactions so record, evidence attachment, job completion, and audit either commit together or roll back together.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Modulith, Spring JDBC, PostgreSQL 17, Flyway, MockMvc, JUnit 6, AssertJ.

## Global Constraints

- Work in the shared backend repository without touching running services.
- Preserve and exclude all unrelated supply/V60 and pre-existing untracked changes.
- Use strict TDD: observe the intended failure before each production change.
- Do not return database, image, ZIP/XML, stack-trace, or internal exception messages to clients.
- Do not push; stage only exact task paths and hunks.

---

### Task 1: Complete MARKET common metadata

**Files:**
- Create: `src/main/resources/db/migration/V61__complete_market_submission_contract.sql`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/market/interfaceadapter/MarketMonitoringRestIntegrationTest.java`

**Interfaces:**
- Produces required core field `MKT_SAMPLE_NAME` with `TEXT`, `EXTENSION`, `GENERIC`, required `true`.
- Preserves `MKT_OBJECT_TYPE` as the typed category binding.

- [ ] Add integration assertions that definition exposes required `MKT_SAMPLE_NAME`, a create without it returns `INVALID_MARKET_RECORD`, and a valid concrete object name persists in detail/list.
- [ ] Run `mvn -Dtest=MarketMonitoringRestIntegrationTest test`; verify the new assertions fail because the field is absent.
- [ ] Add V61 inserts for core definition, generic field definition, page mounting, column grouping, and MARKET applicability.
- [ ] Add `MKT_SAMPLE_NAME` to shared MARKET test request metadata.
- [ ] Re-run the focused test and verify all MARKET command tests pass.

### Task 2: Attach private evidence to MARKET records

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/evidence/application/EvidencePhotoService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringDraft.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketRecordView.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/interfaceadapter/MarketMonitoringCommandController.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/market/interfaceadapter/MarketEvidenceIntegrationTest.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/market/interfaceadapter/MarketMonitoringRestIntegrationTest.java`

**Interfaces:**
- `MarketMonitoringDraft(..., List<UUID> evidencePhotoIds)` defensively copies IDs.
- `EvidencePhotoService.attachToMarket(ids, recordId, regionCode, subjectId)` and `marketPhotos(recordId)` expose only MARKET-specific attachment operations.
- MARKET detail response returns bounded evidence metadata without bytes or storage URLs.

- [ ] Add real HTTP tests for missing photos, one valid photo, cross-owner photo, and mixed valid/unavailable IDs with database counts proving zero side effects.
- [ ] Run the new test and verify missing/unavailable photos incorrectly create records or the response lacks evidence.
- [ ] Add explicit MARKET evidence operations and inject the evidence service into MARKET creation/view paths.
- [ ] Upgrade the existing MARKET integration fixture so every create uses a fresh staged photo while saves can reuse attached IDs.
- [ ] Re-run `MarketEvidenceIntegrationTest,MarketMonitoringRestIntegrationTest`; verify green.

### Task 3: Add a MARKET import port and versioned template

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketImportPort.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/market/application/package-info.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringService.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/MarketImportTemplate.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/MarketImportFormatIntegrationTest.java`

**Interfaces:**
- `MarketImportPort.validateImportDraft(MarketMonitoringDraft draft)` performs read-only domain, permission, and evidence validation.
- `MarketImportPort.importDraft(MarketMonitoringDraft draft)` creates through the normal transactional MARKET path.
- Template columns are `productCode`, typed core values, six common metadata fields, and `evidencePhotoId`.

- [ ] Add a failing template/port integration test for the exact header and required concrete object name.
- [ ] Run the focused test and verify the MARKET import endpoint is 404.
- [ ] Add the narrow named interface, port implementation, and template constants.
- [ ] Run compile and the focused contract test.

### Task 4: Implement atomic CSV/XLSX MARKET imports

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/MarketImportService.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/MarketImportController.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/domain/ImportRowOutcome.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/MarketImportFormatIntegrationTest.java`

**Interfaces:**
- Endpoints: template, upload, retry, and error export under `/api/v1/imports/market`.
- `ImportRowOutcome.recordId` is domain-neutral while preserving the serialized `recordId` field.
- Uses domain code `MARKET` in import reservation keys.

- [ ] Add failing CSV mixed-row and XLSX success/replay tests with real uploaded photos and database assertions.
- [ ] Verify RED: endpoint missing or no MARKET rows created.
- [ ] Implement bounded format selection, canonical CSV storage, per-row parsing, permission/evidence pre-validation, all-row-first validation, atomic writes, retry, error CSV, and safe exception mapping.
- [ ] Verify GREEN: XLSX creates once under replay; a mixed CSV reports every row and creates/attaches nothing.

### Task 5: Verify architecture, migration replay, and exact delivery

**Files:**
- Modify only if required: `src/test/java/com/cofco/qiqihar/graintrade/masterdata/infrastructure/BootFlywayStartupTest.java`
- Modify only if required: `src/test/java/com/cofco/qiqihar/graintrade/masterdata/infrastructure/FlywayMigrationReplayTest.java`
- Create: `docs/superpowers/reports/2026-08-08-market-submission-evidence-import-report.md`

**Interfaces:**
- V1-to-V61 migration replay remains repeatable.
- Spring Modulith sees MARKET application as the only imported MARKET surface.

- [ ] Run all focused MARKET, evidence, import, and architecture tests.
- [ ] Run `mvn -Dtest=FlywayMigrationReplayTest test` and verify a clean V1-to-V61 replay plus a no-op second startup.
- [ ] Run full `mvn verify` and record the exact test count and result.
- [ ] Review `git diff --check`, exact changed paths, error strings, and staged diff.
- [ ] Commit only MARKET/evidence/import/V61/report files; leave supply/V60 and pre-existing files untouched.
