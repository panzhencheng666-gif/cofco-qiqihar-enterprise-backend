# Design Sample Backend Contract Implementation Plan

> **For agentic workers:** Execute inline in this single task. Do not dispatch subagents or expand beyond Backend.

**Goal:** Make design-sample validation and persistence share one canonical value mapping, add scoped authoritative re-query, and prove object-type business-field switching and event atomicity.

**Architecture:** Keep V157/V159/V160 immutable. Extend the existing metadata service with an internal normalized result used by both pure validation and point writes, and add a read-scoped point lookup through the existing repository.

**Tech Stack:** Java 21, Spring Boot 4.1, JDBC, PostgreSQL 17/PostGIS, JUnit 6, MockMvc.

## Global Constraints

- Backend only; base SHA `48d3a4d472aeb40bf25fdcca1942d1689eb20f7d`.
- No `surveyYear`, approval workflow, import flow, 489 preload, 2332 restore, formal-network change, overview change, or production/shared service access.
- Use task PostgreSQL port `55495` and exact protected database `qiqihar_enterprise_test`.
- Do not modify V157, V159, or V160 and do not invent fields.

---

### Task 1: Canonical shared validation result

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/designsample/metadata/application/ValidatedDesignSampleValues.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/designsample/metadata/application/DesignSampleMetadataService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/designsample/point/application/DesignSamplePointService.java`
- Test: `src/test/java/com/cofco/qiqihar/graintrade/designsample/point/interfaceadapter/DesignSamplePointRestIntegrationTest.java`

- [ ] Add a failing lifecycle assertion showing decimal strings are re-queried as JSON numbers and free text is trimmed.
- [ ] Run the focused test and confirm the representation assertion fails.
- [ ] Add `validateForPersistence(...)` returning immutable normalized values plus existing value states.
- [ ] Make public pure validation adapt that result without changing its response contract.
- [ ] Make create and update persist the normalized map returned by metadata validation.
- [ ] Re-run the focused test and existing metadata tests.

### Task 2: Scoped authoritative lookup

**Files:**
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/designsample/point/application/DesignSamplePointService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/designsample/point/interfaceadapter/DesignSamplePointController.java`
- Test: `src/test/java/com/cofco/qiqihar/graintrade/designsample/point/interfaceadapter/DesignSamplePointRestIntegrationTest.java`

- [ ] Add failing tests for `GET /{id}` success and region-scope denial.
- [ ] Run the focused tests and confirm 404/mapping failure.
- [ ] Add a read-only service lookup that loads the row and requires its authoritative region in the current read scope.
- [ ] Add the strict UUID path mapping without new query parameters.
- [ ] Re-run focused tests.

### Task 3: Object-type business-field switch and rollback contract

**Files:**
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/designsample/point/interfaceadapter/DesignSamplePointRestIntegrationTest.java`

- [ ] Add an agricultural-input-store create with the four V157 agricultural-input fields.
- [ ] Add a rejected switch carrying an inapplicable agricultural-input field and assert the original row/audit/outbox counts are unchanged.
- [ ] Add a successful trader switch with purchase and sale prices and assert old fields are absent from API and stored JSONB.
- [ ] Delete and assert zero master-row residue plus create/update/delete audit and outbox events.
- [ ] Run the design-sample point and metadata test classes.

### Task 4: Review, gates, and PR

**Files:**
- Review every changed file against this spec and repository SOP.

- [ ] Run V159/V160 upgrade replay and generic SSE focused tests on port 55495.
- [ ] Run `git diff --check`, inspect status/diff, and self-review the base-to-head diff.
- [ ] Fetch `origin/main`, bind the final gate to its SHA, and run JDK 21 `mvn -B -ntp verify`.
- [ ] Commit only owned files, push the feature branch, create the Backend PR, and wait for the unique native `Backend CI / JDK 21 / Maven verify` run for the exact head.
- [ ] Do not merge. Stop only the task PostgreSQL server and preserve its data directory and feature worktree.
