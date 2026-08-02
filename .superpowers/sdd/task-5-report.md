# Task 5 implementation report — production monitoring

## Round-two disposition

All Critical, Important, and Minor findings in `task-5-review-round2.md` are fixed.

- Production NEW, VIEW, RETURN, create, save-draft, submit, approve, return-write, and object-definition requests are versioned against the active product/page context. Stale completions cannot open dialogs, report errors, or refresh a newly selected product.
- The shared list controller no longer performs search or history callbacks inside React state updaters. Same-page commits update the App route source, and real browser back/forward restores filter and pagination state. StrictMode performs one initial search and one search per paging command.
- Object-definition responses are latest-request-wins. Values for fields excluded by the winning definition are pruned while values for still-applicable fields are retained; saving is disabled while applicability is loading.
- The production editor now derives core-field order, labels, units, and value types from the database page definition. Production navigation selects a dynamically loaded product instead of hard-coding CORN.
- The App uses one canonical production-page predicate for hash validation, navigation context, and render dispatch. Unsupported production page kinds are rejected before definition or production-list requests.
- The backend uses `ProductionValidationException` to distinguish aggregate validation from infrastructure failures. `ProductionRecordService` maps only the typed validation exception to 400 and lets unknown runtime failures propagate.
- `ProductionActionPolicy` is the single status-to-action policy consumed by both JDBC list projection and REST detail projection.
- HTTP transport failures are translated by the frontend infrastructure adapter into the application port's typed `ProductionRepositoryFailure`; UI code has no `HttpError` or status inspection. A dependency-cruiser rule prevents module application layers from depending on React, UI, or infrastructure.
- The former 551-line production page is split into a list/page composition, a UI command hook, a database-driven editor component, and typed repository failure handling. Action failures have independent retry/dismiss UI and never occupy the list-query retry slot.

## Round-two TDD evidence

- Backend RED: the action-policy test first failed compilation before `ProductionActionPolicy` existed; a service test showed a repository runtime failure being converted to `ClientRequestException`; a negative-area test showed an untyped domain exception escaping instead of the stable client contract. The targeted backend suite then passed 11/11.
- Frontend RED: StrictMode replay produced three searches instead of two; unsupported production page kinds reached page-definition/list requests; the shell selected CORN despite a SOYBEAN fixture; delayed CORN NEW and out-of-order TYPE_A/TYPE_B definitions mutated the active UI; and action failures lacked an independent retry surface.
- Typed transport RED: four adapter cases (400/401/409/503) initially rethrew `HttpError` instead of a typed repository failure. All six adapter tests then passed.
- Regression coverage now includes real `history.back()`/`history.forward()`, StrictMode paging, delayed NEW/VIEW/RETURN loads, delayed create/save/submit/approve/return writes, object-definition reordering and hidden-value pruning, dynamic core labels/types/units, canonical route rejection, and independent action retry/clear behavior.

## Round-two verification

- Backend: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify` — BUILD SUCCESS; 112 tests, 0 failures/errors/skips; Flyway validated and replayed all 15 migrations; executable jar built.
- Frontend: `npm run verify` — Prettier, ESLint, dependency-cruiser (53 modules / 141 dependencies), 14 Vitest files / 72 tests, TypeScript, and Vite production build all passed.
- `git diff --check` passed in both repositories before commit. No migration was added or edited, frontend `.idea/` remained untouched, and no push was performed.

## Round-two commits

- Backend implementation: `678e2f3 fix(production): address round-two backend review`
- Frontend implementation: `39c6ddf fix(production): address round-two frontend review`
- This report is committed separately after both implementation hashes were known.

## Round-two hand-off

- All round-two findings have targeted regression coverage; independent round-three review is the next gate.
- Task 9 still owns full authentication/authorization and auditing. Task 5 continues to use the authenticated servlet principal through `CurrentActor`.

## Round-one disposition

All Critical and Important findings in `task-5-review-round1.md` are fixed.

- Canonical production routes remain `#/pages/PRODUCTION/MONITORING/{CORN|SOYBEAN|RICE}`; the shell production link uses the same parseable route and product switching preserves the production context.
- The frontend now executes real NEW, VIEW, PUT/save-draft, submit, approve, and return operations. Row actions are the intersection of page-configured actions and the record's database-derived allowed actions.
- Draft revision rehydrates and preserves the aggregate. Only DRAFT and RETURNED records can be revised; state transitions update state/version without replacing fact tables.
- Quality, cost, insurance, and subsidy facts have database definitions constrained by product, object type, page, and category. The form and list consume database-driven definitions/projections without hard-coded `PROD_*` mappings or business fact seed data.
- Product/cultivar compatibility and fact applicability are enforced in forward-only V14/V15 migrations and in the service boundary.
- Invalid states, invalid filters/select/date values, and optimistic-lock failures return stable 400/409 responses; writes authenticate before revealing record existence.
- `reportedAt` is server-generated from an injected Asia/Shanghai clock. Survey-date validation uses the same zone in Java and PostgreSQL.
- Decimal inputs remain strings at the JSON boundary. Java normalizes inputs to scale 4 before multiplication, checks database bounds, and returns scale-4 strings.
- A shared list-page controller is used by market, production, and workflow pages for context reset, definition error/retry, stale-response suppression, unmount cleanup, out-of-range page clamping, and history normalization.
- Production list facts are batch-loaded once per page (one query per fact category), eliminating the prior per-row quality query.

## Migrations

- Added `V14__normalize_production_facts_and_concurrency.sql` and `V15__fix_production_fact_applicability_trigger.sql` only; V1–V13 were not edited.
- V14 adds fact definitions/applicability, category/code foreign keys for all four fact tables, product/cultivar consistency, record versions, and Shanghai date semantics.
- V15 is the forward-only correction for the polymorphic fact-applicability trigger.
- Migration replay succeeds from an empty schema through V15 and across the repository's staged replay checkpoints.

## TDD evidence

- Domain RED: targeted compilation failed before the full aggregate/revision API existed; the resulting tests cover state-safe revision, fact preservation, and decimal precision.
- Migration RED: V14 constraint/applicability tests initially failed, including the trigger defect subsequently corrected by V15.
- REST RED: dynamic definition and complete write/concurrency contracts initially failed (including a missing endpoint and trigger failure). The integration suite now covers authorization, strict query validation, dynamic definitions, all fact categories, three products, version conflicts, illegal transitions, future dates, cultivar mismatch, and fact preservation.
- Frontend RED: route tests exposed production links being rewritten, adapter tests exposed the hard-coded projection and missing writes, and page tests exposed no-op operations. The final page tests exercise dynamic fact creation, VIEW→PUT, submit, approve, return, allowed-action filtering, and stable 401/409 feedback.
- Existing market/workflow lifecycle tests exercise the shared controller's retry, stale-response, unmount, clamp, and history behavior after extraction.

## Verification

- Backend: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn verify` — BUILD SUCCESS; 106 tests, 0 failures/errors/skips; Flyway validated and replayed 15 migrations; executable jar built.
- Frontend: `npm run verify` — Prettier, ESLint, dependency-cruiser, 14 Vitest files / 56 tests, TypeScript, and Vite production build all passed.
- `git diff --check` passed in both repositories before commit.
- Frontend `.idea/` remained untouched. No push was performed.

## Commits

- Backend implementation: `79151b1 fix(production): close round-one contract gaps`
- Frontend implementation: `ea649b7 fix(production): complete monitoring write workflow`
- This report is committed separately after both implementation hashes were known.

## Hand-off

- Task 9 still owns full authentication/authorization and auditing; Task 5 continues to consume the servlet principal through `CurrentActor` and never fabricates a user.
- Independent round-two review remains the next gate.
