# Annual Sample Network Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a governed yearly real-sample network and compare it with the 2,332 yearless village design reference points across Backend, Frontend map, and the 63182 Web workspace.

**Architecture:** Backend owns the yearly network, approval lifecycle, village-code comparison, and coverage contract. Frontend consumes the comparison contract for map layers while preserving the existing nine real-point icons. Web consumes the same contract for annual governance and compact coverage strips; 63182 remains the only acceptance entry.

**Tech Stack:** Java 21, Spring Boot, Spring Modulith, JDBC, PostgreSQL/PostGIS, Flyway, React, TypeScript, Vite, Vitest, Testing Library, Playwright, Node 24.

## Global Constraints

- Work only on `feature/20260823-sample-network-comparison` in all three repositories.
- Never copy the database or derive business results outside the 8090 authority.
- The 2,332 design points never enter production, price, inventory, or supply calculations.
- Preserve the existing nine real sample-point icon paths, colors, and meanings.
- Use village region codes for matching; coordinates are display and quality evidence only.
- Candidate generation copies stable sample-point references only, never prior-year business facts.
- Use JDK 21 and Node 24.

---

### Task 1: Persist governed annual networks

**Files:**
- Create: `src/main/resources/db/migration/V133__govern_annual_sample_networks.sql`
- Test: `src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/infrastructure/AnnualSampleNetworkMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `registry.sample_network_year`, `registry.sample_network_membership`, status constraints, indexes, and least-privilege grants.

- [ ] **Step 1: Write the failing migration integration test**

Assert that a network can be created in `DRAFT`, that one sample point can occur once per year, that membership village codes must resolve to `VILLAGE`, and that invalid statuses and self-review publication fail.

- [ ] **Step 2: Run the test and verify the missing-relation failure**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -Dtest=AnnualSampleNetworkMigrationIntegrationTest test`

Expected: FAIL because `registry.sample_network_year` does not exist.

- [ ] **Step 3: Add the migration**

Create the two tables and constraints from the design. Use composite primary key `(network_year, sample_point_id)`, foreign keys to `registry.sample_point` and `platform.region`, `version bigint not null default 0`, and audit actor/time columns. Add `registry.village_design_sample_point` as a read-only view over VILLAGE regions joined to `platform.region_location`; do not duplicate rows.

- [ ] **Step 4: Run the migration test**

Expected: PASS and an exact design-view count of 2332.

- [ ] **Step 5: Commit Backend migration**

`git add src/main/resources/db/migration/V133__govern_annual_sample_networks.sql src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/infrastructure/AnnualSampleNetworkMigrationIntegrationTest.java && git commit -m "feat(sample-network): govern annual sample memberships"`

### Task 2: Implement annual network lifecycle

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/AnnualSampleNetworkView.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/AnnualSampleNetworkService.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/application/package-info.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/infrastructure/JdbcAnnualSampleNetworkRepository.java`
- Create: `src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network/interfaceadapter/AnnualSampleNetworkController.java`
- Test: `src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network/interfaceadapter/AnnualSampleNetworkRestIntegrationTest.java`

**Interfaces:**
- Produces: list/detail/member queries and candidate-generation, member-decision, submit, review, and publish commands under `/api/v1/sample-networks`.
- Consumes: `AccessControl.require("BUSINESS_IMPORT", region)`, `AccessControl.require("BUSINESS_APPROVE", region)`, `SeparationOfDutiesPolicy`, `BusinessAuditRecorder`.

- [ ] **Step 1: Write failing HTTP lifecycle tests**

Cover: empty 2027 draft creation from published 2026 membership; reference-only carry-forward; optimistic version conflict; add/pause/remove; submit; independent approval; self-approval rejection; publish; published-network mutation rejection; region authorization.

- [ ] **Step 2: Run the focused test**

Expected: FAIL with 404 for `/api/v1/sample-networks/2027`.

- [ ] **Step 3: Implement views and repository**

Use records `NetworkSummary`, `NetworkDetail`, `MembershipView`, and `CoverageSummary`. Repository mutations must lock the network row and update with `where version=:expectedVersion`; zero updates raise the existing conflict contract.

- [ ] **Step 4: Implement service and controller**

Candidate generation inserts prior `ACTIVE` members as `CANDIDATE/CARRIED_FORWARD`. Submit and approve record audit events. Publish requires `IN_REVIEW`, an approved independent decision, and no remaining `CANDIDATE` member.

- [ ] **Step 5: Run tests and architecture checks**

Run the focused test plus the repository's Spring Modulith architecture test.

- [ ] **Step 6: Commit Backend lifecycle**

`git add src/main/java/com/cofco/qiqihar/graintrade/samplepoint/network src/test/java/com/cofco/qiqihar/graintrade/samplepoint/network && git commit -m "feat(sample-network): add annual governance lifecycle"`

### Task 3: Expose the village comparison contract

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/overview/application/SampleNetworkComparison.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/overview/application/OverviewSamplePointRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/overview/application/OverviewSamplePointService.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/JdbcOverviewSamplePointRepository.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/OverviewSamplePointController.java`
- Test: `src/test/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/SampleNetworkComparisonRestIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/v1/overview/sample-network-comparison?year&regionCode&productCode`.
- Response fields: `scope`, `coverage`, `designPoints`, `actualPoints`; every point has `layerType`, village code, coordinates, and state.

- [ ] **Step 1: Write failing comparison tests**

Construct villages with no member, active/no-approved-data, active/approved-data, multiple active members, and coordinate anomaly. Assert 2332 denominator at city scope and that returned/older-year records never count as approved submissions.

- [ ] **Step 2: Run the focused test**

Expected: FAIL because the endpoint is absent.

- [ ] **Step 3: Implement repository SQL**

Read design points from `registry.village_design_sample_point`. Join published `ACTIVE` membership by `village_region_code`; count only effective approved production/market records for the requested year/product. Use region ancestry, not coordinate distance, for scope.

- [ ] **Step 4: Implement service and endpoint**

Return `DESIGN_REFERENCE` and `ANNUAL_ACTUAL` layer types. Validate year/product/region with existing overview scope rules. Do not add design rows to existing business metrics.

- [ ] **Step 5: Run comparison and existing overview consistency tests**

Expected: all pass; existing overview metric values remain unchanged.

- [ ] **Step 6: Commit Backend comparison**

`git add src/main/java/com/cofco/qiqihar/graintrade/overview src/test/java/com/cofco/qiqihar/graintrade/overview && git commit -m "feat(overview): expose annual sample coverage comparison"`

### Task 4: Add Frontend map comparison layers

**Files:**
- Modify: `src/modules/overview/domain/overviewSamplePoint.ts`
- Modify: `src/modules/overview/application/ports/OverviewSamplePointRepository.ts`
- Modify: `src/modules/overview/infrastructure/http/HttpOverviewSamplePointRepository.ts`
- Modify: `src/modules/overview/infrastructure/http/HttpOverviewSamplePointRepository.spec.ts`
- Modify: `src/modules/overview/ui/components/OverviewSamplePointPanel.tsx`
- Modify: `src/modules/overview/ui/components/OverviewSamplePointPanel.spec.tsx`
- Modify: `src/modules/overview/ui/components/TerrainReliefBoundaryMap.tsx`
- Create: `src/modules/overview/ui/presentation/sampleNetworkLayers.ts`
- Create: `src/modules/overview/ui/presentation/sampleNetworkLayers.spec.ts`
- Modify: `src/app/styles/global.css`

**Interfaces:**
- Consumes: Backend `SampleNetworkComparison`.
- Produces: `actual | design | comparison` layer mode and neutral design marker presentation.

- [ ] **Step 1: Write failing repository and presentation tests**

Assert exact query forwarding, stale-response protection, layer separation, accessible labels, gray design target marker, and unchanged real icon path/color mapping.

- [ ] **Step 2: Run focused Node 24 tests**

Expected: FAIL because comparison types and methods do not exist.

- [ ] **Step 3: Add domain types and HTTP method**

Add typed `SampleNetworkComparison`, `SampleNetworkDesignPoint`, `SampleNetworkActualPoint`, `SampleNetworkCoverage`, and `loadNetworkComparison(scope)`.

- [ ] **Step 4: Add layer selector and marker rendering**

Keep current aggregate behavior above village depth. At village/deep depth render real markers from existing icon functions and design markers with a dedicated neutral target path/class. Clear stale layer results on year/product/region/mode changes.

- [ ] **Step 5: Run focused tests and existing map tests**

Expected: comparison tests pass and existing nine-icon assertions remain byte-for-byte unchanged.

- [ ] **Step 6: Commit Frontend map feature**

`git add src/modules/overview src/app/styles/global.css && git commit -m "feat(overview): compare design and annual sample points"`

### Task 5: Add 63182 annual management and coverage strips

**Files:**
- Modify: `src/platform/api/realtimeBusinessRepository.ts`
- Modify: `src/platform/api/realtimeBusinessRepository.spec.ts`
- Create: `src/business/samplepoint/AnnualSampleNetworkPanel.tsx`
- Create: `src/business/samplepoint/AnnualSampleNetworkPanel.spec.tsx`
- Create: `src/business/analysis/SampleNetworkCoverageStrip.tsx`
- Create: `src/business/analysis/SampleNetworkCoverageStrip.spec.tsx`
- Modify: `src/business/analysis/ProductionAnalysisPanel.tsx`
- Modify: `src/business/analysis/MarketAnalysisPanel.tsx`
- Modify: `src/pages/supply/SupplyBalancePanel.tsx`
- Modify: `src/business/PortalWorkspaces.tsx`
- Modify: `src/business/PortalWorkspaces.spec.tsx`

**Interfaces:**
- Consumes: annual-network command/query endpoints and comparison coverage.
- Produces: governed annual network workspace and one compact coverage strip reused on production, market, and supply pages.

- [ ] **Step 1: Write failing API/client and component tests**

Assert candidate generation, member decisions, submit/review/publish, optimistic version errors, capability-based action hiding, 2332-as-denominator copy, and explicit no-data states.

- [ ] **Step 2: Run focused Node 24 tests**

Expected: FAIL because client methods/components do not exist.

- [ ] **Step 3: Implement repository types and methods**

Add exact methods `listSampleNetworks`, `getSampleNetwork`, `generateSampleNetworkCandidates`, `updateSampleNetworkMember`, `submitSampleNetwork`, `reviewSampleNetwork`, `publishSampleNetwork`, and `getSampleNetworkComparison`.

- [ ] **Step 4: Implement annual panel and coverage strip**

Use existing enterprise table/action components. The strip shows design villages, annual active points, approved reporting points, covered villages, uncovered, multiple, and anomalies; it never renders business metrics.

- [ ] **Step 5: Integrate without redesigning page architecture**

Mount the governance panel in the existing sample-governance workspace. Insert the strip directly below existing filters in the three analysis pages; leave existing charts and layout intact.

- [ ] **Step 6: Run focused tests, bundle budget, UI inventory, lint, and build**

Expected: all pass on Node 24.

- [ ] **Step 7: Commit Web feature**

`git add src/platform/api src/business src/pages/supply && git commit -m "feat(sample-network): add annual governance and coverage"`

### Task 6: Cross-repository verification

- [ ] **Step 1: Run Backend JDK 21 focused and full required gates**
- [ ] **Step 2: Replay Flyway on empty `qiqihar_enterprise_test` and trusted V132 upgrade**
- [ ] **Step 3: Run Frontend Node 24 tests, lint, architecture, format, and build**
- [ ] **Step 4: Run Web Node 24 tests, lint, architecture, bundle budget, UI inventory, and build**
- [ ] **Step 5: Record exact Backend/Web/Frontend branch SHAs and contract dependency**
- [ ] **Step 6: Proceed to the separate local reset and runtime-publish plan**
