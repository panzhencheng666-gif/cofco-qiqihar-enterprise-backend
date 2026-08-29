# Existing Sample Observation Workspace Redesign Implementation Plan

> Execute serially in the shared dirty worktree. Use test-first red/green slices, preserve every unrelated user change, and do not commit, reset, clean, switch branches, merge, push, or access any remote business database.

**Goal:** Replace the temporary stacked existing-sample form with a formal page-level update workspace, add authorized object-type/name filtering, and make every editable field follow the same backend applicability contract used for validation and persistence.

**Architecture:** Extend the existing `formalsampleobservation` eligibility resource with server-authoritative object identity and bounded filters. Keep sample identity locked at save time. Make market definition metadata the single field source for both Web rendering and backend validation; a new migration removes sales base price applicability from deep processors, feed mills, and breeding factories without rewriting history. Web uses mutually exclusive ledger/update modes and a reusable filter/list/editor workspace.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring JDBC/PostgreSQL/Flyway, React 19, TypeScript, Vitest/Testing Library, Node 24/Vite, managed local ports 8090/63182/63200.

---

## Task 1: Lock object filters and identity in the backend REST contract

**Files:**

- Modify: `src/test/java/com/cofco/qiqihar/graintrade/formalsampleobservation/interfaceadapter/FormalSampleObservationRestIntegrationTest.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/interfaceadapter/FormalSampleObservationController.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/FormalSampleObservationRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/application/EligibleFormalSample.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/formalsampleobservation/infrastructure/JdbcFormalSampleObservationRepository.java`

1. Add failing integration cases for `objectTypeCode` and `keyword`, combined filtering, no-result behavior, invalid object type, keyword length, region isolation, and returned `objectTypeCode/objectTypeName`.
2. Run `./scripts/mvn-jdk21.sh -Dtest=FormalSampleObservationRestIntegrationTest test` and verify the new assertions fail because the parameters/response fields do not exist.
3. Add optional query parameters, trim and cap keyword at 100 characters, validate the object type against active master data, and pass only normalized values to the repository.
4. Extend the lateral eligibility projection with the latest record's authoritative object type. Add parameterized type and escaped case-insensitive name/region search predicates after authorized-region filtering. Do not concatenate input into SQL.
5. Re-run the focused test green. Add a save assertion proving a client cannot change the selected sample's object type through payload data.

## Task 2: Make market sales-price applicability authoritative

**Files:**

- Create: `src/main/resources/db/migration/V152__align_market_price_applicability.sql`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/market/interfaceadapter/MarketMonitoringRestIntegrationTest.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/formalsampleobservation/interfaceadapter/FormalSampleObservationRestIntegrationTest.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/market/application/MarketMonitoringService.java`
- Modify only if required by the existing definition query: `src/main/java/com/cofco/qiqihar/graintrade/market/infrastructure/JdbcMarketMonitoringRepository.java`

1. Add failing definition tests proving `MKT_SALE_BASE_PRICE` is absent for `DEEP_PROCESSOR`, `FEED_MILL`, and `BREEDING_FACTORY`, while an unchanged applicable type still returns it.
2. Add failing save tests: those three types save with purchase price and applicable facts but no sales price; injected inapplicable sales price is rejected; applicable required-field omission is rejected with the public 422 error contract.
3. Run the two focused integration classes and observe the expected red failures from current metadata and unconditional sale-price parsing.
4. Add V152 metadata-only changes that deactivate/remove sales-price applicability for the three types without updating historical records.
5. Change market parsing to derive allowed and required core/fact fields from the same definition returned by `/api/v1/market-record-definitions`. Reject unknown/inapplicable fields, parse optional values only when applicable, and retain all existing numeric/option validation.
6. Re-run focused tests green, then run migration replay to prove clean-database and upgraded-database consistency.

## Task 3: Lock the Web repository contract

**Files:**

- Modify: `src/platform/api/realtimeBusinessRepository.spec.ts`
- Modify: `src/platform/api/realtimeBusinessRepository.ts`

1. Add failing tests for exact eligibility query names (`domain`, `productCode`, `regionCode`, `year`, `observedAt`, `objectTypeCode`, `keyword`), typed object identity parsing, definition loading, POST payload, `Idempotency-Key`, and API error propagation.
2. Run `PATH="/opt/homebrew/opt/node@24/bin:$PATH" npm exec vitest -- run src/platform/api/realtimeBusinessRepository.spec.ts` and observe red.
3. Extend `EligibleFormalSample` and the list input type. Preserve optional parameters as `undefined` rather than inventing empty codes. Keep the existing one-call save resource; do not call draft/submit/approve routes.
4. Re-run the focused repository spec green.

## Task 4: Build the page-level update workspace test-first

**Files:**

- Modify: `src/business/formal-sample/ExistingSampleObservationPanel.spec.tsx`
- Modify: `src/business/formal-sample/ExistingSampleObservationPanel.tsx`
- Modify: `src/business/market/ProductMarketCollectionWorkspace.spec.tsx`
- Modify: `src/business/market/ProductMarketCollectionWorkspace.tsx`
- Modify: `src/business/production/ProductProductionCollectionWorkspace.spec.tsx`
- Modify: `src/business/production/ProductProductionCollectionWorkspace.tsx`
- Modify: `src/business/market/LogisticsMonitoringWorkspace.spec.tsx`
- Modify: `src/business/market/LogisticsMonitoringWorkspace.tsx`

1. Add failing component/page tests for mutually exclusive “采集台账 / 已有样本数据更新” modes; the original query, import, correction and create actions must remain in ledger mode.
2. Add failing workspace tests for object-type select, debounced or explicit keyword search, authorized sample results, selected locked identity, loading/empty/error states, and return to ledger without losing ledger filters.
3. Add metadata-driven form tests using real `MarketDefinition` literals: all applicable core and group fields render in server order; locked/date-derived fields do not render as editable; deep/feed/breeding types have no sales price; changing object type reloads eligible samples and definition.
4. Add idempotency tests: failed retry reuses the key, post-attempt field change rotates the key, success triggers eligibility/list refresh and shows actual `officialSavedAt`, `projectionVersion`, and `synchronizedModules`.
5. Run the four focused specs and verify they fail for missing mode/filter/dynamic behavior.
6. Implement a controlled page mode in all three collection workspaces. In update mode render only the update workspace; do not stack the ledger below it.
7. Replace `fieldsByDomain` with definition adapters. For market, map `coreFields` to `coreValues` and grouped fields to `facts` using definition membership, not code prefixes. Prefill only applicable fields from `latestValues`.
8. Re-run the four focused specs green.

## Task 5: Apply the approved enterprise layout and responsive rules

**Files:**

- Modify: `src/business/market-monitoring.css`
- Modify the component files from Task 4 only when semantic wrappers or labels are required.

1. Add a CSS contract test for a flat page mode header, filter band, bounded two-pane work area, 1px separators, square/low-radius controls, visible focus indicators, and no fixed overlay.
2. Implement the approved IBM Carbon-inspired treatment using the platform's existing blue, white/light-gray surfaces, 4px spacing rhythm, no decorative green card, no shadows, and a 48px touch minimum at narrow breakpoints.
3. At `max-width: 900px`, switch list/editor to one column. At phone width, make actions full-width and ensure every grid item has `min-width: 0`.
4. Run focused specs, `npm run format:check` on task-owned files, `npm run lint`, `npm run architecture`, and `npm run build` with Node 24.

## Task 6: Security and adversarial contract review

**Files:**

- Add regression assertions to the focused backend/Web specs above; change production code only for validated findings.

1. Review parameterized query handling, wildcard escaping, keyword/object-code bounds, mass assignment, unauthorized sample enumeration, time-of-check/time-of-use, idempotency races, same-key/different-payload conflicts, untrusted response rendering, and error-detail leakage.
2. For every validated issue, first add a failing regression test, observe red, then apply the smallest fix and observe green.
3. Run backend focused integration tests plus `FlywayMigrationReplayTest`; run Web focused specs plus repository tests. Run `git diff --check` or `git diff --no-index --check` for every task-owned untracked file.

## Task 7: Proportional regression, local publish, and real 63182 acceptance

1. Backend: run affected market/formal-observation tests, migration replay, then the repository's standard JDK 21 build gate. Record tests separately from build evidence.
2. Web: run focused tests, full Vitest if focused gates are clean, format check, lint, architecture, inventory/budget checks required by the publish script, and production build with Node 24.
3. Publish only with the existing local runtime workflow. Never access cloud/production/remote databases. Verify source build and deployed asset SHA-256 equality, then health/listeners for 8090, 63182 and 63200. Keep `/usr/bin/caffeinate -i` alive.
4. In the real 63182 browser, verify ledger/update mode separation at desktop and narrow widths; filter by object type; search a real enterprise; verify locked identity and complete applicable fields; save one local formal observation; refresh and requery the value.
5. Verify the same literal through market overview, market analysis and report APIs/UI. Compare regional supply-balance version/result before and after and prove it is unchanged.
6. Report task-owned files, red/green evidence, security findings/fixes, build/hash/health evidence and browser acceptance as separate claims. Preserve all pre-existing dirty files and leave no commit.

## Task 4A: Unify collection-ledger and update-field contracts

**Files:**

- Create: `src/business/formal-sample/formalSampleObservationFields.ts`
- Create: `src/business/formal-sample/formalSampleObservationFields.spec.ts`
- Modify: `src/business/formal-sample/ExistingSampleObservationPanel.tsx`
- Modify: `src/business/market/ProductMarketCollectionWorkspace.spec.tsx`
- Modify: `src/business/market/ProductMarketCollectionWorkspace.tsx`
- Modify: `src/business/production/ProductProductionCollectionWorkspace.spec.tsx`
- Modify: `src/business/production/ProductProductionCollectionWorkspace.tsx`
- Modify: `src/business/market/LogisticsMonitoringWorkspace.spec.tsx`
- Modify: `src/business/market/LogisticsMonitoringWorkspace.tsx`

1. Add failing tests proving the ledger exposes all editable definition fields that the update form exposes, while keeping identity/time/status columns separate and excluding locked fields from the editable contract.
2. Add failing market coverage for definition-only fields such as ending inventory, stock outflow and processing input; reverse the former assertions that deliberately hid those fields.
3. Extract one pure definition normalizer used by the update form and all three realtime ledgers. Merge definitions for the object types present on the current page, de-duplicate by field code and keep stable group/field order.
4. Render persisted cell values by the same server field code. Missing/inapplicable values display “—”. Fixture-only fallbacks remain isolated from the realtime path.
5. Treat definition loading failure as an actionable ledger error and never silently fall back to the old static field set in the realtime path.
6. Add CSS contract assertions for equal filter-control heights and label baselines, then apply the approved Carbon-derived flat layout without changing business navigation.
7. Run the focused red/green tests before the existing security, regression, publish and real-browser tasks.
