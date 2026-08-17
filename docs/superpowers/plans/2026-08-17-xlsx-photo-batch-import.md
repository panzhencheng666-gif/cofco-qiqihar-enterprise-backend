# XLSX Photo Batch Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy a local-only, photo-aware XLSX import flow for all production, market, and logistics templates so sparse rows become governed drafts, complete records enter the existing independent review flow, approval makes data immediately available without a publisher, and the local business workspace is cleared of all prior test transactions before real entry begins.

**Architecture:** Keep the XLSX generator as the single template contract, add one generic import-draft staging aggregate and one secure photo package boundary, and promote a completed import draft through the existing production/market/logistics services. Approval continues to use each domain's `APPROVED` state as the published state; the audit/outbox/SSE chain broadcasts the committed change. The formal business Web sends one workbook plus all referenced photos in the same multipart request and exposes imported drafts for completion.

**Tech Stack:** Java 21, Spring Boot, Spring MVC multipart, Spring JDBC, Flyway/PostgreSQL, JUnit 5/MockMvc, TypeScript, React, Vitest, Playwright, Vite, existing local launchd runtime.

## Global Constraints

- Local environment only; no cloud, preproduction, production, DNS, traffic, paid service, or external object-storage mutation.
- Each nonblank sample row requires only a sample name and governed region. It may reference 0–5 JPG/JPEG/PNG photos; absent or invalid photos never block importing, saving, submitting, reviewing, or auto-publishing valid business data.
- Completely blank rows are ignored; blank numeric cells never become zero and blank text never becomes a fabricated default.
- The package contains exactly one `.xlsx` plus zero or more optional referenced photos; users never handle internal photo UUIDs and images are not embedded in Excel.
- A submitter cannot approve or return their own record. A record without an eligible independent regional reviewer remains a draft and submission fails with a clear message.
- `APPROVED` is the automatically published business state. There is no business-data publisher role, no manual publish action, and no pending-publication queue.
- Only approved canonical production, market, and logistics records feed formal queries, analysis, overview, and reporting.
- Preserve the backend's existing three uncommitted template-validation files and the overview frontend's existing three user-modified files; stage and commit only task-owned paths.
- The final destructive step applies only to the local database after a recoverable checkpoint and read-only inventory. It removes every local business test transaction: production, market, logistics and supply records and details; drafts and revisions; pending, returned and approved work items; import jobs and row results; staged and attached test evidence; test report previews, exports and publications; notifications; and derived business projections and caches. It preserves users, roles, regions, products, object types, page definitions, migrations, lifecycle policy, and immutable audit history.

---

## Repository and File Map

### Backend root

`/Users/federal/Documents/Codex/2026-08-15/cofco-security-incident-finalization-sol-high/work/observable-analysis-20260816/cofco-qiqihar-enterprise-backend`

- `BusinessImportWorkbook.java`: common XLSX metadata, instructions, validation formulas, blank-row handling, and versioned workbook parsing.
- `ProductionImportTemplate.java`, `MarketImportTemplate.java`, `LogisticsImportTemplate.java`: domain-specific columns built from authoritative definitions plus the common photo filename column.
- `BusinessImportPhotoPackage.java`: normalizes optional multipart photo filenames, resolves up to five names referenced by a row, and returns non-blocking photo warnings.
- `ImportDraft.java`, `ImportDraftRepository.java`, `JdbcImportDraftRepository.java`: governed sparse import-draft aggregate and persistence.
- `ImportRowOutcome.java`, `ImportJobView.java`, `JdbcImportJobRepository.java`: durable non-blocking row warning codes/messages and warning counts.
- `ImportDraftService.java`, `ImportDraftController.java`: list/read/delete/promote draft API and ownership/region checks.
- `ImportDraftRowExecutor.java`: one `REQUIRES_NEW` transaction per row so valid rows survive neighboring failures.
- `EvidencePhotoService.java`, `EvidencePhotoRepository.java`, `JdbcEvidencePhotoRepository.java`: secure batch-photo staging and later production/market/logistics attachment.
- `ProductionImportService.java`, `MarketImportService.java`, `LogisticsImportService.java`: parse rows, enforce the minimum anchor contract, and create generic import drafts instead of incomplete canonical records.
- `ProductionRecordService.java`, `MarketMonitoringService.java`, `LogisticsService.java`: reviewer-route gate, draft promotion, photo attachment, and approval audit action.
- `V121__stage_photo_aware_business_import_drafts.sql`: expand-only draft, photo mapping, nullable staged capture location, logistics evidence consistency, and indexes.
- Integration tests under `src/test/java/.../importing/interfaceadapter`: 9 user-template catalog plus internal applicability matrix, multipart package, partial row success, draft promotion, independent review, auto-publish event.

### Business Web root

`/Users/federal/Documents/Codex/2026-08-15/cofco-security-incident-finalization-sol-high/work/observable-analysis-20260816/cofco-qiqihar-enterprise-web`

- `realtimeBusinessRepository.ts`: multipart workbook/photo contract and import-draft endpoints.
- `businessImportWorkflow.ts`: terminal status and draft result messaging.
- `BusinessImportStatus.tsx`: success/failure/draft counts and result actions.
- `RealtimeBusinessOperationsPanel.tsx`: production/market multi-file picker and imported-draft completion entry.
- `RealtimeLogisticsOperationsPanel.tsx`: logistics multi-file picker, photo review, and imported-draft completion entry.
- Corresponding `*.spec.ts(x)` files: repository FormData and user interaction contracts.
- `e2e/live/xlsx-imports.e2e.ts`: live download, fill, import, submit, review, and automatic-refresh acceptance.

---

### Task 1: Freeze the Versioned Template and Photo Filename Contract

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/infrastructure/BusinessImportWorkbook.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ProductionImportTemplate.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/MarketImportTemplate.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/LogisticsImportTemplate.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/importing/domain/BusinessImportWorkbookTest.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/BusinessImportTemplateMatrixIntegrationTest.java`

**Interfaces:**
- Produces: `BusinessImportWorkbook.PHOTO_FILENAMES_CODE = "evidencePhotoNames"` and label `现场照片文件名（可选，最多5张，分号分隔）`.
- Produces: `Context.contractVersion()` and `Context.contractDigest()` read from the instruction sheet and matched before data parsing.
- Produces: every current production, market, and logistics workbook with the common photo column last.

- [ ] **Step 1: Add failing workbook tests**

Add assertions that exactly 9 user-facing templates exist (3 products × 3 domains), each generated template has a `填报说明` sheet, carries nonblank `模板版本` and `契约摘要`, puts the photo column last, permits blank non-anchor business cells, applies year/month validation to row 2, and ignores a completely blank row. Production and market expose one optional object-type column inside the product template; object-type applicability remains an internal row-routing rule.

```java
assertThat(template.headers().getLast()).isEqualTo(BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
assertThat(template.labels().getLast()).isEqualTo("现场照片文件名（可选，最多5张，分号分隔）");
assertThat(BusinessImportWorkbook.context(bytes, template.domainCode()).contractVersion())
        .isEqualTo(template.contractVersion());
assertThat(sheetXml).contains("LEN(TRIM(A2))=0");
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
mvn -q -Dtest=BusinessImportWorkbookTest,BusinessImportTemplateMatrixIntegrationTest test
```

Expected: failure because current metadata does not expose version/digest for every domain and current generated templates omit the shared photo column.

- [ ] **Step 3: Implement the common contract**

Define the constants once and append the same rule from each domain template:

```java
public static final String PHOTO_FILENAMES_CODE = "evidencePhotoNames";
public static final String PHOTO_FILENAMES_LABEL = "现场照片文件名（可选，最多5张，分号分隔）";

public static ColumnRule photoFilenameRule() {
    return new ColumnRule(PHOTO_FILENAMES_CODE, "TEXT", "PHOTO_FILENAMES", false,
            List.of(), 0, 0, "可留空；有照片时填写最多 5 个文件名，中文或英文分号分隔");
}
```

Write `模板版本` and `契约摘要` into the instruction sheet and return them from `context`. Make all domain business `ColumnRule.required` values false except the sample name and region; the photo column is optional. Keep server-generated reporter/status fields out of editable columns. Reject absent/mismatched metadata as `IMPORT_CONTRACT_MISMATCH` with the Chinese old-template message.

- [ ] **Step 4: Run the focused template matrix**

Run the command from Step 2.

Expected: all tests pass; the public catalog contains exactly 3 production, 3 market,
and 3 logistics templates. The separate internal applicability matrix still proves the 9 production,
15 market, and 3 logistics routing combinations without describing them as user templates.

- [ ] **Step 5: Commit only the template contract**

```bash
git add src/main/java/com/cofco/qiqihar/graintrade/importing/infrastructure/BusinessImportWorkbook.java \
  src/main/java/com/cofco/qiqihar/graintrade/importing/application/ProductionImportTemplate.java \
  src/main/java/com/cofco/qiqihar/graintrade/importing/application/MarketImportTemplate.java \
  src/main/java/com/cofco/qiqihar/graintrade/importing/application/LogisticsImportTemplate.java \
  src/test/java/com/cofco/qiqihar/graintrade/importing/domain/BusinessImportWorkbookTest.java \
  src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/BusinessImportTemplateMatrixIntegrationTest.java
git commit -m "fix: unify photo-aware XLSX template contracts"
```

### Task 2: Add the Expand-Only Import Draft Schema

**Files:**
- Create: `src/main/resources/db/migration/V121__stage_photo_aware_business_import_drafts.sql`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/importing/infrastructure/PhotoAwareImportDraftMigrationTest.java`

**Interfaces:**
- Produces: `platform.business_import_draft`, `platform.business_import_draft_evidence`, and `platform.import_job_photo`.
- Produces: draft states `DRAFT`, `PROMOTED`, `DISCARDED` and a version counter.
- Produces: nullable `warning_code` and `warning_message` on `platform.import_row_result` while keeping `outcome_code='IMPORTED'` for rows whose business data succeeded.
- Produces: nullable `captured_at`, `capture_latitude`, and `capture_longitude` for staged batch photos; attached canonical evidence still follows domain submission rules.

- [ ] **Step 1: Write migration tests**

Test both a fresh database and an upgrade from V120. Assert foreign keys, unique `(import_job_id,row_number)`, unique normalized filename per job, 0–5 evidence rows per draft through the service boundary, logistics attachment consistency, and unchanged master data counts.

```java
assertThat(columns("platform", "business_import_draft"))
        .contains("import_draft_id", "domain_code", "product_code", "object_type_code",
                "sample_name", "region_code", "survey_period", "values_json", "missing_fields_json",
                "state_code", "created_by", "import_job_id", "source_row_number", "version");
```

- [ ] **Step 2: Verify the migration test fails**

```bash
mvn -q -Dtest=PhotoAwareImportDraftMigrationTest test
```

Expected: failure because V121 and its tables do not exist.

- [ ] **Step 3: Create V121**

Use UUID primary keys, JSONB checks, requester and region indexes, restrictive foreign keys, and these uniqueness guarantees:

```sql
UNIQUE (import_job_id, source_row_number),
UNIQUE (import_job_id, normalized_filename),
CHECK (domain_code IN ('PRODUCTION','MARKET','LOGISTICS')),
CHECK (state_code IN ('DRAFT','PROMOTED','DISCARDED')),
CHECK (source_row_number > 1),
CHECK (version >= 0)
```

Do not drop, rename, or reinterpret any V120 column. Recreate `evidence.evidence_photo_consistency` with a `LOGISTICS` branch and retain the existing production/market branches.

- [ ] **Step 4: Run migration tests**

Run the command from Step 2.

Expected: pass on fresh and V120-upgrade paths; Flyway reports schema version 121.

- [ ] **Step 5: Commit the migration**

```bash
git add src/main/resources/db/migration/V121__stage_photo_aware_business_import_drafts.sql \
  src/test/java/com/cofco/qiqihar/graintrade/importing/infrastructure/PhotoAwareImportDraftMigrationTest.java
git commit -m "feat: add governed business import drafts"
```

### Task 3: Stage and Resolve Secure Photo Packages

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/BusinessImportPhotoPackage.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/evidence/application/EvidencePhotoService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/evidence/application/EvidencePhotoRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/evidence/infrastructure/JdbcEvidencePhotoRepository.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/importing/application/BusinessImportPhotoPackageTest.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/evidence/interfaceadapter/EvidencePhotoRestIntegrationTest.java`

**Interfaces:**
- Consumes: workbook cell `evidencePhotoNames`.
- Produces: `BusinessImportPhotoPackage.stage(UUID jobId, List<PhotoPart> parts, String watermarkPrefix)`.
- Produces: `List<UUID> resolve(UUID jobId, String cellValue)` with exact NFC-normalized filename matching.
- Produces: `EvidencePhotoService.uploadForImport(...)` that uses import time and `定位待补充` without inventing coordinates.

- [ ] **Step 1: Write package-security tests**

Cover Chinese/English semicolons, Unicode NFC, duplicate normalized names, zero and six names, missing files, unreferenced files, traversal characters, MIME spoofing, corrupt images, 10 MiB limit, 40-megapixel limit, and one photo referenced by two rows. Assert every photo problem becomes a warning and does not invalidate the business row.

```java
assertThat(photoPackage.parseNames("地块一.jpg；地块二.png"))
        .containsExactly("地块一.jpg", "地块二.png");
assertThatThrownBy(() -> photoPackage.parseNames("../secret.jpg"))
        .isInstanceOf(ClientRequestException.class);
```

- [ ] **Step 2: Verify focused tests fail**

```bash
mvn -q -Dtest=BusinessImportPhotoPackageTest,EvidencePhotoRestIntegrationTest test
```

Expected: failure because package resolution and missing-location batch upload are absent.

- [ ] **Step 3: Implement secure staging**

Use `Normalizer.normalize(name, Normalizer.Form.NFC)`, reject `/`, `\\`, control characters and duplicates, and accept only decoded JPEG/PNG content. The import watermark format is:

```java
String watermark = "%s | %s | 导入时间 %s | 定位待补充"
        .formatted(domainLabel, sampleName, uploadedAt.atZone(ZONE));
```

Store each staged photo in the existing private evidence store and bind it to the import job through `platform.import_job_photo`; do not expose storage keys or absolute paths.

- [ ] **Step 4: Run focused tests**

Run the command from Step 2.

Expected: all photo package and existing online evidence upload tests pass.

- [ ] **Step 5: Commit secure photo staging**

```bash
git add src/main/java/com/cofco/qiqihar/graintrade/importing/application/BusinessImportPhotoPackage.java \
  src/main/java/com/cofco/qiqihar/graintrade/evidence/application/EvidencePhotoService.java \
  src/main/java/com/cofco/qiqihar/graintrade/evidence/application/EvidencePhotoRepository.java \
  src/main/java/com/cofco/qiqihar/graintrade/evidence/infrastructure/JdbcEvidencePhotoRepository.java \
  src/test/java/com/cofco/qiqihar/graintrade/importing/application/BusinessImportPhotoPackageTest.java \
  src/test/java/com/cofco/qiqihar/graintrade/evidence/interfaceadapter/EvidencePhotoRestIntegrationTest.java
git commit -m "feat: stage secure XLSX photo packages"
```

### Task 4: Persist Sparse Rows as Independent Governed Drafts

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/domain/ImportDraft.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ImportDraftRepository.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ImportDraftRowExecutor.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/infrastructure/JdbcImportDraftRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/domain/ImportRowOutcome.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ImportJobView.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/infrastructure/JdbcImportJobRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ProductionImportService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/MarketImportService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/LogisticsImportService.java`
- Modify: all three import controllers.
- Modify: all three import REST integration tests.

**Interfaces:**
- Produces: multipart field `file` and optional repeated `photos`.
- Produces: row-level outcomes whose `businessRecordId` is the import draft UUID until promotion.
- Produces: `warningRows` on `ImportJobView`; photo warnings are persisted while the row outcome remains `IMPORTED`.
- Produces: per-row atomicity with `Propagation.REQUIRES_NEW`.

- [ ] **Step 1: Add failing multipart and sparse-row tests**

For each domain submit a generated workbook containing one sparse row without photos and one row referencing an invalid photo. Assert both business drafts are committed, the second row carries a photo warning rather than a business error, no canonical domain record is created, and rerunning the same idempotency key creates neither duplicate draft nor photo.

```java
mvc.perform(multipart("/api/v1/imports/production")
        .file(workbook)
        .file(photo("photos", "稻谷样本一.jpg"))
        .header("Idempotency-Key", key)
        .param("productCode", "RICE")
        .param("objectTypeCode", "FARMER"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.importedRows").value(2))
        .andExpect(jsonPath("$.data.failedRows").value(0))
        .andExpect(jsonPath("$.data.warningRows").value(1));
```

- [ ] **Step 2: Verify the tests fail**

```bash
mvn -q -Dtest=ProductionImportRestIntegrationTest,MarketImportRestIntegrationTest,LogisticsImportRestIntegrationTest test
```

Expected: current controllers accept only one workbook and current services require complete canonical domain drafts or process the batch atomically.

- [ ] **Step 3: Implement the draft row executor**

Use this transaction boundary:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public ImportDraft create(ImportDraft draft, List<UUID> evidenceIds) {
    ImportDraft stored = repository.insert(draft);
    repository.bindEvidence(stored.id(), evidenceIds);
    return stored;
}
```

The three services identify their anchors with these exact codes:

```java
PRODUCTION: sampleName="PROD_SAMPLE_NAME", region="regionCode"
MARKET:     sampleName="MKT_SAMPLE_NAME",  region="MKT_REGION"
LOGISTICS:  sampleName="LOG_SAMPLE_NAME",  region="LOG_REGION"
```

Authorize `BUSINESS_IMPORT` for every resolved region. Store all other nonblank cells in `values_json`, calculate missing-field labels from the authoritative definition, and call the row executor inside a loop that records an error without canceling other rows.

- [ ] **Step 4: Run import integration tests**

Run the command from Step 2 plus:

```bash
mvn -q -Dtest=QueuedBusinessImportIntegrationTest,ProductionImportConcurrencyIntegrationTest test
```

Expected: sparse rows become drafts, partial success works, queue/retry remains durable, and idempotency is preserved.

- [ ] **Step 5: Commit sparse draft import**

Stage only the files listed in this task and commit:

```bash
git commit -m "feat: import sparse XLSX rows as governed drafts"
```

### Task 5: Promote Drafts and Require an Independent Regional Reviewer

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/application/ImportDraftService.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/ImportDraftController.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/shared/security/application/ReviewRouteRepository.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/shared/security/infrastructure/JdbcReviewRouteRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/production/application/ProductionRecordService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/logistics/application/LogisticsDraft.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/logistics/application/LogisticsService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/evidence/application/EvidencePhotoService.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/ImportDraftPromotionIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/v1/import-drafts?domainCode=&productCode=&stateCode=DRAFT`.
- Produces: `GET /api/v1/import-drafts/{id}` and `DELETE /api/v1/import-drafts/{id}` for the creating user while still DRAFT.
- Produces: `POST /api/v1/import-drafts/{id}/promote` with a domain-native draft body; returns the canonical record ID.
- Produces: `ReviewRouteRepository.hasIndependentReviewer(String regionCode, String submitterSubjectId, Instant at)`.

- [ ] **Step 1: Add failing promotion and reviewer-route tests**

Assert ownership/region access, optimistic version conflict, zero and five bound photos, promotion once only, optional logistics photo attachment, and the missing-reviewer submission message.

```java
assertThatThrownBy(() -> reviewRoutes.requireIndependentReviewer(region, submitter, now))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("当前地区尚未配置独立审核员");
```

- [ ] **Step 2: Verify tests fail**

```bash
mvn -q -Dtest=ImportDraftPromotionIntegrationTest,ProductionRecordRestIntegrationTest,MarketRecordRestIntegrationTest,LogisticsRestIntegrationTest test
```

Expected: import-draft API and reviewer routing are absent; logistics has no evidence list.

- [ ] **Step 3: Implement routing and promotion**

The reviewer query must require an enabled active employee, an effective `BUSINESS_REVIEWER` role carrying `BUSINESS_APPROVE`, an effective region scope matching the record region or an ancestor scope, and a subject different from the submitter. Call it immediately before each domain transitions from editable state to `PENDING_REVIEW`.

Promotion reads the draft with `FOR UPDATE`, injects its evidence IDs into the domain draft, creates a canonical `DRAFT`, attaches the evidence to `PRODUCTION`, `MARKET`, or `LOGISTICS`, and marks the import draft `PROMOTED` with the canonical ID in the same transaction.

- [ ] **Step 4: Run promotion and domain tests**

Run the command from Step 2.

Expected: all tests pass; a missing reviewer blocks submission but never deletes the saved draft.

- [ ] **Step 5: Commit promotion and reviewer routing**

Stage only Task 5 files and commit:

```bash
git commit -m "feat: promote imports through independent review"
```

### Task 6: Prove Approval Is Automatic Publication and Realtime

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/production/application/ProductionRecordService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/logistics/application/LogisticsService.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/ImportApprovalAutoPublicationIntegrationTest.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/notification/interfaceadapter/BusinessEventStreamIntegrationTest.java`

**Interfaces:**
- Produces: audit actions `PRODUCTION_RECORD_AUTO_PUBLISHED`, `MARKET_RECORD_AUTO_PUBLISHED`, and `LOGISTICS_RECORD_AUTO_PUBLISHED` on approval.
- Produces: committed SSE events that cause formal record, work-item, overview, analysis, and report caches to refresh.

- [ ] **Step 1: Write failing automatic-publication tests**

Create and submit a promoted import record, approve it as another regional reviewer, and assert in one committed outcome: status `APPROVED`, no manual publishing work item, approved-only query includes the record, pending-only query excludes it, and SSE contains the auto-published action.

```java
assertThat(auditActions(recordId)).containsExactly(
        "PRODUCTION_RECORD_CREATED", "PRODUCTION_RECORD_SUBMITTED",
        "PRODUCTION_RECORD_AUTO_PUBLISHED");
assertThat(manualPublicationWorkItems(recordId)).isZero();
```

- [ ] **Step 2: Verify the tests fail on action naming or event expectation**

```bash
mvn -q -Dtest=ImportApprovalAutoPublicationIntegrationTest,BusinessEventStreamIntegrationTest test
```

- [ ] **Step 3: Align approval audit actions**

Retain `APPROVED` as the canonical published state and replace only the domain approval audit action with `*_AUTO_PUBLISHED`. Do not create a `PUBLISHED` command, permission, role, controller endpoint, or queue.

- [ ] **Step 4: Run approval, separation-of-duties, analytics, and SSE tests**

```bash
mvn -q -Dtest=ImportApprovalAutoPublicationIntegrationTest,BusinessEventStreamIntegrationTest,ProductionActionPolicyTest,MarketRecordTest,LogisticsRecordTest test
```

Expected: all pass and self-approval remains rejected.

- [ ] **Step 5: Commit automatic publication evidence**

```bash
git commit -m "feat: auto-publish approved imported records"
```

### Task 7: Update the Formal Business Web for Workbook Plus Photos

**Files (business Web root):**
- Modify: `src/platform/api/realtimeBusinessRepository.ts`
- Modify: `src/platform/api/realtimeBusinessRepository.spec.ts`
- Modify: `src/business/importing/businessImportWorkflow.ts`
- Modify: `src/business/importing/businessImportWorkflow.spec.ts`
- Modify: `src/business/importing/BusinessImportStatus.tsx`
- Modify: `src/business/realtime/RealtimeBusinessOperationsPanel.tsx`
- Modify: `src/business/realtime/RealtimeBusinessOperationsPanel.spec.tsx`
- Modify: `src/business/realtime/RealtimeLogisticsOperationsPanel.tsx`
- Modify: `src/business/realtime/RealtimeLogisticsOperationsPanel.spec.tsx`

**Interfaces:**
- Consumes: one workbook and `readonly File[]` photos.
- Produces: `importProductionWorkbook(workbook, photos, productCode, objectTypeCode)`, where `photos` may be empty, equivalent market/logistics functions, and import-draft list/read/promote operations.

- [ ] **Step 1: Write failing repository and component tests**

Assert one multi-select input accepts `.xlsx,.jpg,.jpeg,.png`, rejects zero or multiple workbooks client-side, accepts a workbook with no photos, appends exactly one `file` and every optional photo as `photos`, displays per-row draft and photo-warning counts, and preserves the selected files while showing validation errors.

```ts
expect(form.getAll("file")).toHaveLength(1);
expect(form.getAll("photos").map((value) => (value as File).name)).toEqual([
  "样本一.jpg",
  "样本二.png",
]);
```

- [ ] **Step 2: Verify Web tests fail**

```bash
npm run test -- --run src/platform/api/realtimeBusinessRepository.spec.ts \
  src/business/importing/businessImportWorkflow.spec.ts \
  src/business/realtime/RealtimeBusinessOperationsPanel.spec.tsx \
  src/business/realtime/RealtimeLogisticsOperationsPanel.spec.tsx
```

Expected: current UI and repository accept only one XLSX file.

- [ ] **Step 3: Implement multi-file import and draft completion**

Partition files exactly once:

```ts
const workbooks = files.filter((file) => file.name.toLowerCase().endsWith(".xlsx"));
const photos = files.filter((file) => /\.(?:jpe?g|png)$/iu.test(file.name));
if (workbooks.length !== 1) {
  throw new Error("IMPORT_PACKAGE_INVALID");
}
```

Append the workbook as `file` and each optional photo as `photos`. Change the label to `导入 XLSX 与照片（照片可选）`, show the concise operation guidance from the design, display photo warnings without turning them into import failures, load successful import drafts after completion, and route “继续填写” through the existing domain editor. On promotion, replace the import draft with the canonical record returned by the backend.

- [ ] **Step 4: Run focused Web verification**

Run the tests from Step 2, then:

```bash
npm run lint
npm run architecture
npm run build
```

Expected: all commands exit 0 and the business bundle builds.

- [ ] **Step 5: Commit Web changes**

```bash
git add src/platform/api/realtimeBusinessRepository.ts \
  src/platform/api/realtimeBusinessRepository.spec.ts \
  src/business/importing/businessImportWorkflow.ts \
  src/business/importing/businessImportWorkflow.spec.ts \
  src/business/importing/BusinessImportStatus.tsx \
  src/business/realtime/RealtimeBusinessOperationsPanel.tsx \
  src/business/realtime/RealtimeBusinessOperationsPanel.spec.tsx \
  src/business/realtime/RealtimeLogisticsOperationsPanel.tsx \
  src/business/realtime/RealtimeLogisticsOperationsPanel.spec.tsx
git commit -m "feat: import XLSX records with photos"
```

### Task 8: Run the Cross-Domain Acceptance Matrix

**Files:**
- Modify: backend `src/test/java/com/cofco/qiqihar/graintrade/importing/interfaceadapter/BusinessImportTemplateMatrixIntegrationTest.java`
- Modify: Web `e2e/live/xlsx-imports.e2e.ts`

**Interfaces:**
- Consumes: all work completed in Tasks 1–7.
- Produces: reproducible evidence for 9 user templates, the separate internal applicability matrix, and two independent sessions.

- [ ] **Step 1: Extend live fixtures to generate valid photos and sparse workbooks**

Use deterministic in-memory PNGs and generated server templates. Cover every production/market/logistics product-object template, then execute one full rice path with operator and reviewer sessions.

- [ ] **Step 2: Run the complete backend import gate**

```bash
mvn -q -Dtest=BusinessImportWorkbookTest,BusinessImportTemplateMatrixIntegrationTest,ProductionImportRestIntegrationTest,MarketImportRestIntegrationTest,LogisticsImportRestIntegrationTest,ImportDraftPromotionIntegrationTest,ImportApprovalAutoPublicationIntegrationTest test
mvn -q -DskipTests package
```

Expected: zero failures and a successful backend package.

- [ ] **Step 3: Run complete Web gates proportional to the change**

```bash
npm run format:check
npm run lint
npm run architecture
npm run test
npm run build
npm run budget
```

Expected: all commands exit 0.

- [ ] **Step 4: Run live two-session acceptance**

Run the local protected Playwright path for `e2e/live/xlsx-imports.e2e.ts`. Assert submitter sees the imported draft, reviewer receives the pending item without a page reload, self-approval is unavailable, reviewer approval changes the record to `APPROVED`, and the submitter/query/overview refresh without a publisher action.

- [ ] **Step 5: Commit acceptance tests**

Commit the backend and Web test changes separately in their owning repositories with message:

```bash
git commit -m "test: cover photo XLSX import and auto-publication"
```

### Task 9: Deploy to the Controlled Local Runtime

**Files:**
- Runtime backend: `/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend`
- Runtime Web build destination as resolved by `scripts/start-local.sh` and `scripts/local-runtime.sh`.

**Interfaces:**
- Consumes: verified backend JAR, Web `dist`, and migration V121.
- Produces: healthy local ports 8090, 63182, and 63200 with source/artifact hashes recorded in a checkpoint.

- [ ] **Step 1: Capture pre-deploy state**

Record `git status`, source commits, artifact SHA-256, running PIDs, `local-runtime.sh status`, Flyway version, and business table counts. Copy only the current runtime artifacts/configuration into a timestamped directory under the runtime `state/checkpoints` folder; never print secrets.

- [ ] **Step 2: Build and bind artifacts**

Copy the verified backend source/runtime files and Web `dist` through the existing local runtime binding procedure. Verify copied file hashes match the source build hashes before restart.

- [ ] **Step 3: Restart only the local stack**

```bash
cd "/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend"
./scripts/local-runtime.sh restart
./scripts/local-runtime.sh status
```

Expected: backend 8090, business Web 63182, and overview 63200 are runtime-owned and healthy; Flyway reaches V121 exactly once.

- [ ] **Step 4: Smoke-test the deployed flow**

Download a rice template from 63182, verify the photo column and row-2 validation, import one sparse row with a real decoded local test image, confirm its import draft and private photo, then delete that smoke-test draft through the controlled cleanup in Task 10.

### Task 10: Remove Local Business Test Data Before Real Entry

**Files:**
- Create outside Git: timestamped local database dump and row inventory in the runtime checkpoint directory.
- Do not add a reusable destructive script to the product repository.

**Interfaces:**
- Consumes: local database only, after Task 9 health and smoke validation.
- Produces: empty business-facing production, market, logistics, supply, workflow, import, evidence, notification, report-history, overview and analysis transaction views while preserving configuration and immutable audit history.

- [ ] **Step 1: Resolve exact local targets read-only**

Query counts and identifying metadata for production records/facts/revisions, market records/facts/extensions/inventory governance, logistics records/values, supply input/calculation/manual-decision records, import drafts/evidence links/job photos/import rows/import jobs, workflow work items, staged/attached test evidence, notification deliveries, report previews/exports/publications sourced from test records, and overview/analysis derived cache or projection rows. Verify the connected database is the loopback local runtime and not a remote host. The inventory must also enumerate every foreign key that points to the parent transaction tables so no dependent test row is silently omitted.

- [ ] **Step 2: Create a recoverable checkpoint**

Use `pg_dump --format=custom` through the runtime's existing secret-safe environment and write the dump plus a SHA-256 manifest under `state/checkpoints/business-test-data-pre-clean-<timestamp>`. Do not echo credentials or connection strings.

- [ ] **Step 3: Apply one explicit transaction**

Delete dependent test-only rows before their parent business records, then import jobs/drafts and staged/attached test evidence. Clear derived business projections and caches so overview totals and record lists are empty immediately after restart. Preserve `platform.business_audit_event` because it is immutable and deliberately does not foreign-key business records; it remains a compliance-only trace and must not rehydrate or appear as a live business record. Roll back automatically if any protected table count changes.

Protected tables include:

```text
platform.security_user
platform.security_user_role
platform.security_user_region_scope
platform.access_role
platform.access_permission
platform.region
platform.product
platform.object_type
platform.field_definition
platform.page_definition
flyway_schema_history
platform.data_lifecycle_policy
```

- [ ] **Step 4: Verify the clean state**

Assert zero production, market, logistics and supply business transactions; zero current, returned or approved business records in user-facing lists; zero open or completed business work items; zero import jobs/drafts; zero test evidence; zero test reports/notifications; and zero overview/analysis values derived from deleted test transactions. Re-run health checks and verify all template-definition, identity, region, product, object, and page-definition counts equal the pre-clean inventory.

- [ ] **Step 5: Final user acceptance handoff**

Report the local URL, deployed source commits and migration V121, exact deleted row counts by domain, checkpoint path and hash, reviewer behavior, and the seven-step user operation guide. State explicitly that cloud/production was not changed.
