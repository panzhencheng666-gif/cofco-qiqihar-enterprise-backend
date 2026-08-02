# Task 5 implementation report — production monitoring

## Round-seven disposition

Both Important findings in `task-5-review-round7.md` are fixed. This round changes only the formal frontend Playwright fixture and its browser contract specification; backend production code and V1–V16 remain untouched.

- Before parsing any value, the production-list fixture now permits exactly `productCode`, `pageKind`, `pageNumber`, `pageSize`, and optional `filter.objectTypeCode`. Unknown keys, undefined filter keys, and case/spelling variants are recorded as unexpected and receive the controlled 400 response.
- Every required core key must occur exactly once; the optional object-type filter may occur zero or one time. Duplicate core/filter keys and empty or whitespace-only values fail closed before a successful fixture response.
- Integer parsing no longer uses JavaScript `Number` on unvalidated input. An ASCII signed-decimal lexical check plus `BigInt` range check mirrors Java `Integer.parseInt`'s accepted sign form and 32-bit range before conversion to number; semantic checks then require a nonnegative page number and a positive, formally configured page size.
- The real-browser contract accepts ordinary integers, URL-encoded leading plus signs, Java's maximum int page number, and all formal object filters. It rejects exponent/decimal notation, whitespace, NaN/Infinity, values above/below int32 range, unknown keys, duplicates, blank values, and misspelled keys. Rejected requests never enter `listQueries`.
- Both contract cases use browser-originated fetches through the same installed Playwright route used by the canonical product and race cases; all application cases continue to assert `unexpectedRequests` is empty.

## Round-seven TDD evidence

- RED: both new browser contract cases failed. The fixture returned 200 for `bogus`, `filter.notDefined`, and a case-variant key, and also accepted `1e3`, `2e1`, `2147483648`, and a whitespace-wrapped integer.
- GREEN: after whitelist/multiplicity validation and lexical int32 parsing were added, both targeted contract cases passed and the complete Chromium suite passed 6/6.

## Round-seven verification

- `npm run verify` passed: Prettier, ESLint, dependency-cruiser (54 modules / 138 dependencies, zero violations), the read-only architecture test, 15 Vitest files / 88 tests, TypeScript, Vite build, and 6 Playwright Chromium cases.
- `npm run e2e -- --repeat-each=3` passed 18/18, including three repetitions of both strict fixture contracts and the pending-write back/forward race.
- `npm audit` reported 0 vulnerabilities, and `git diff --check` passed. Frontend production sources, the formal backend, V1–V16, and legacy/dashboard repositories remained untouched. No push was performed.

## Round-seven commits

- Frontend strict fixture parsing: `431543e test(production): mirror strict list query parsing`
- This report is committed separately after the frontend hash was known.

## Round-seven hand-off

- Both round-seven findings have targeted real-browser regression coverage; exact independent re-review is the next gate.

## Round-six disposition

Both Important findings in `task-5-review-round6.md` are fixed. This round changes only the formal frontend E2E fixture, page object, and browser specifications; backend production code and V1–V16 remain untouched.

- The fixture's production object-type contract now uses the formal V3/V16 codes and labels: `FARMER` / 农户, `VILLAGE_COMMITTEE` / 村委会, and `AGRICULTURAL_TECH_STATION` / 农技站. `VILLAGE` appears only as an explicitly rejected negative probe.
- Production-list routing now validates before any successful fulfillment: the method must be GET, `productCode` must be CORN/SOYBEAN/RICE, `pageKind` must occur once and equal `MONITORING`, page number must be a nonnegative safe integer, page size must be one of the formal 20/50/100 options, and any object-type filter must use a formal production code.
- Invalid list requests are recorded in `unexpectedRequests`, return a controlled 400 with `INVALID_E2E_PRODUCTION_LIST_QUERY`, and never enter `listQueries`. The existing application cases continue to require `unexpectedRequests` to remain empty, so a future client omission or contract mismatch fails the browser gate.
- A dedicated real-browser contract case performs fetches through the installed Playwright route fixture. It proves all three formal object types succeed while missing/wrong `pageKind`, legacy `VILLAGE`, invalid product/page/page-size values, and a non-GET method fail closed.
- All three canonical product cases now assert the exact browser-rendered object-type option labels and values, in addition to the existing canonical UI/form/list coverage.

## Round-six TDD evidence

- RED: the targeted browser run passed only 1/5. CORN, SOYBEAN, and RICE each rendered the legacy `{行政村, VILLAGE}` option instead of the formal codes, and the contract probe exposed the former open/throw behavior for invalid list requests.
- GREEN: after the minimal fixture validation and formal option correction, the same targeted Chromium run passed 5/5.

## Round-six verification

- `npm run verify` passed: Prettier, ESLint, dependency-cruiser (54 modules / 138 dependencies, zero violations), the read-only architecture test, 15 Vitest files / 88 tests, TypeScript, Vite build, and 5 Playwright Chromium cases.
- `npm run e2e -- --repeat-each=3` passed 15/15, including three repetitions each of the fail-closed fixture contract and pending-write back/forward race.
- `npm audit` reported 0 vulnerabilities, and `git diff --check` passed. The formal backend, V1–V16, legacy/dashboard repositories, and frontend production sources remained untouched. No push was performed.

## Round-six commits

- Frontend fixture contract: `de0020c test(production): harden browser API fixture`
- This report is committed separately after the frontend hash was known.

## Round-six hand-off

- Both round-six findings have targeted real-browser regression coverage; final independent review is the next gate.

## Round-five disposition

Both Important findings in `task-5-review-round5.md` are fixed. The changes are test/tooling only: backend production code and V1–V16 remain untouched.

- The frontend now has a Playwright 1.62.1 Chromium project in the normal `npm run verify` gate. It starts the strict-port Vite server itself, runs headless with one worker, retains traces and failure screenshots, and does not reuse an unknown existing server.
- Three real-browser cases load the canonical CORN, SOYBEAN, and RICE hashes through the production `App`. Each proves normalized history state, dynamic three-product navigation, product-specific heading/column/value rendering, and the product-specific field within all four Chinese form groups.
- The hardest pending-write case holds a real submit request, changes to the FARMER filter, pages forward, uses actual browser back/forward, then settles the write. Three controlled page-1 responses are completed newest-first and stale-last; the assertions prove refresh uses only the restored page/filter and stale browser-event-loop completions cannot replace the latest DOM result or hash.
- E2E synchronization is request/route/response/DOM driven. The only final browser turn is two `requestAnimationFrame` callbacks after the two stale responses are delivered; no time-based sleeps or `networkidle` waits are used.
- The backend REST integration matrix now adds six successful approval cases: CORN, SOYBEAN, and RICE across FARMER and VILLAGE_COMMITTEE. Each selects formal V16 definitions, creates all four fact categories, submits version 0, approves version 1, then verifies APPROVED/version 2, actions, and all four categories in the transition response, detail response, and filtered list projection.

## Round-five TDD evidence

- Browser-runner RED: before the runner was added, `npm run e2e` failed because the script did not exist. After the Playwright dependency, typed configuration, API route fixture, page object, and browser specifications were added, the first Chromium run passed 4/4.
- Browser stability: the complete E2E suite passed three consecutive repetitions in one invocation (12/12), including three repetitions of the real back/forward pending-write race.
- Backend approval was a missing proof rather than a production defect. The new six-context test passed against the existing application and V16 database with no production or migration change.

## Round-five verification

- Backend: JDK 21.0.11 `mvn verify` — BUILD SUCCESS; 142 tests, 0 failures/errors/skips; the production REST integration class contributed 29 cases, including the 6 new successful approvals. PostgreSQL 17.10 validated and replayed all 16 migrations; the executable JAR was built.
- Frontend: `npm run verify` — Prettier, ESLint, dependency-cruiser (54 modules / 138 dependencies, zero violations), the read-only architecture test, 15 Vitest files / 88 tests, TypeScript, Vite production build, and 4 Playwright Chromium cases all passed.
- Stability and supply chain: `npm run e2e -- --repeat-each=3` passed 12/12; `npm audit` reported 0 vulnerabilities. The exercised runtime was Playwright 1.62.1 with Chromium 151.0.7922.34.
- `git diff --check` passed in both repositories. `git diff --exit-code 4385753 -- src/main/resources/db/migration` proved V1–V16 unchanged. The legacy/dashboard repositories and frontend `.idea/` remained untouched. No push was performed.

## Round-five commits

- Backend approval matrix: `34ecf72 test(production): cover formal approval matrix`
- Frontend browser gate: `9c7d489 test(production): add Playwright browser gate`
- This report is committed separately after both repository hashes were known.

## Round-five hand-off

- Both round-five findings now have real-browser or formal-database regression coverage; a sixth independent review is the next gate.
- Task 9 still owns full authentication/authorization and auditing. Task 5 continues to use the authenticated servlet principal through `CurrentActor`.

## Round-four disposition

All Important findings in `task-5-review-round4.md` are fixed. No migration or backend production-code change was required, and V1–V16 remain untouched.

- The shared list controller now adopts every newly decided query synchronously in `queryRef` before starting a search or invoking a route callback. This covers initial normalization, history restore, user filter changes, page and page-size changes, out-of-range page clamping, and history normalization; the render path never mutates the ref.
- Search request-version ordering still suppresses stale responses. A write finishing in the narrow window after a restored search starts but before the query state effect/render can now refresh only the restored query, never the previous filter/page query.
- The real App regression is parameterized across `create`, `saveDraft`, `submit`, `approve`, and `returnForCorrection`. Each repository write remains pending while real browser back/forward restores page 1 plus the FARMER filter; the first restored search stays pending while the write settles. The test proves both subsequent searches use the latest query, an older pending result cannot overwrite the latest result, and the final filter/page/result/hash remain aligned.
- Architecture probes are tracked fixtures under `scripts/architecture-fixtures`, outside `src`, the production TypeScript project, and the normal source dependency graph. They use an independent TypeScript root/config while importing the production boundary rule.
- The read-only probe covers resolved React, application-to-barrel-to-UI, application-to-barrel-to-infrastructure, domain-to-application-to-barrel-to-UI, and a TypeScript path alias to UI. The architecture test launches two guards concurrently, compares their exact result, and proves `git status` is unchanged. The guard has no filesystem write/delete operations, so concurrent execution and interruption cannot leave source-tree residue.
- The REST integration suite now parameterizes CORN, SOYBEAN, and RICE across FARMER, VILLAGE_COMMITTEE, and AGRICULTURAL_TECH_STATION using only formal V16 facts selected and checked through the definition endpoint.
- All six FARMER/VILLAGE contexts create, detail, list, submit, return, and save records containing a formal quality, cost, insurance, and subsidy fact. All three agricultural-tech-station contexts create/detail/list quality-only records.
- Nine negative agricultural-tech-station cases independently submit COST, INSURANCE, and SUBSIDY facts for each product. Every request returns stable `INAPPLICABLE_PRODUCTION_FACT` 400, and actor-scoped record plus all four fact-table counts prove no partial business write. Cleanup deletes only actor-scoped production records and never mutates master data.

## Round-four TDD evidence

- Query race RED: all five real App write paths searched the restored `{pageNumber: 1, objectTypeCode: FARMER}` query and then incorrectly searched the old `{pageNumber: 0, values: {}}` query when the deferred write settled before the query-ref effect. After synchronous query adoption, the shared/App targeted suite passed 54/54.
- Architecture RED: the initial contract failed because stable fixtures did not exist. After fixtures were added, two concurrent executions of the former guard produced no comparable JSON result because it still created/deleted fixed files in `src`. The read-only guard then passed its parallel Node test and the normal source graph remained at zero violations.
- The backend finding was a missing proof rather than a failing production behavior: after a test-only table-name typo in the partial-write counter was corrected, the new 23-case formal REST suite passed without any backend production or migration change. This is intentionally a test-only backend commit rather than a manufactured implementation change.

## Round-four verification

- Backend: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home mvn verify` — BUILD SUCCESS; 136 tests, 0 failures/errors/skips; PostgreSQL validated and replayed all 16 migrations from empty and staged schemas; executable JAR built.
- Frontend: `npm run verify` — Prettier, ESLint, normal dependency-cruiser (54 modules / 138 dependencies, zero violations), concurrent read-only architecture test (1/1), 15 Vitest files / 88 tests, TypeScript, and Vite production build all passed.
- `git diff --check` passed in both repositories before the implementation/test commits. V1–V16, frontend `.idea/`, and legacy repositories remained untouched. No push was performed.

## Round-four commits

- Backend test matrix: `1a22ed8 test(production): cover round-four object matrix`
- Frontend implementation: `942bcb8 fix(production): address round-four frontend review`
- This report is committed separately after both repository hashes were known.

## Round-four hand-off

- All round-four findings have targeted regression coverage; a fifth independent review is the next gate.
- Task 9 still owns full authentication/authorization and auditing. Task 5 continues to use the authenticated servlet principal through `CurrentActor`.

## Round-three disposition

All Critical, Important, and Minor findings in `task-5-review-round3.md` are fixed.

- Forward-only V16 seeds the confirmed production fact master data: 4 ordered Chinese category records, 19 decimal fact definitions, and 102 product/object applicability records. It inserts zero production business records and leaves V1–V15 untouched.
- Confirmed quality definitions cover CORN (water, test weight, impurity, imperfect grain, mildew), SOYBEAN (protein, oil yield, imperfect grain, water, impurity), and RICE (water, milling yield, brown-rice yield, impurity). Cost, insurance, and subsidy definitions use the confirmed Chinese labels, units, and scales.
- The legacy qualitative toxin field is deliberately not guessed into the decimal-only V14 model. It remains a future typed-field extension rather than being silently represented with an incorrect value type.
- Category code, Chinese label, and order are now category master data. The application service generically assembles ordered groups, including empty and future categories, and fails fast if a definition references an unregistered category. The controller only maps the application result.
- `ProductionActionPolicy` is now consumed only by the application service. JDBC returns raw status/configured actions, and both list and detail/write responses receive allowed actions from the same application boundary.
- Frontend definitions preserve category metadata and field order, display Chinese group labels, accept future category codes without misrouting them to subsidy values, and render an unsupported-group notice until a typed editor exists.
- Object-type selection is committed atomically with its matching definition. A failed or mismatched response leaves the prior object type, definition, facts, and save target coherent; retry can complete the requested switch and prune only inapplicable facts.
- Delayed write completions refresh the controller's latest query, so same-page browser back/forward cannot overwrite restored filter results with a stale closure.
- Market, production, and workflow now share one application-layer contract for exact definition identity and route-query normalization, including optional product identity, filter whitelisting, and finite/integer pagination validation.
- The dependency guard covers module and shared application/domain sources, matches resolved React paths, and follows transitive/barrel reachability into React, UI, and infrastructure. The architecture command includes temporary direct-React and domain-through-application-barrel probes and verifies that both are rejected before cleaning them up.
- Component coverage now renders CORN, SOYBEAN, and RICE and submits one migrated quality fact plus migrated cost, insurance, and subsidy facts for each product in a real FARMER definition context.

## Round-three TDD evidence

- Migration RED: the formal V15 database had no confirmed fact definitions/applicability and the new exact-count test failed. V16 made the 4-category / 19-definition / 102-applicability / zero-business-record contract pass. A second RED showed V14's category check blocked a future category; the V16 forward fix replaced it with a category-master foreign key.
- Backend boundary RED: tests exposed controller/JDBC ownership of grouping and action policy. Repository raw rows plus application form/view models moved both decisions into the application service; tests cover future-category grouping, empty groups, unknown-category failure, and list/detail actions.
- Architecture RED: direct React and domain-to-application-barrel-to-shared-UI probes escaped the former rule. The reachable rule and executable guard self-test now reject both resolved paths.
- Object-switch RED: a failed B request left B selected with A facts, and a response claiming B while containing definition A was accepted. The editor now retains A on failure, validates response identity, and switches/prunes atomically after a successful retry.
- History/write RED: a real App test performed a deferred submit, changed same-page filters with `history.pushState` plus `popstate`, then observed the old query refresh. `refreshLatest` now reads the controller query ref at completion time.
- Shared-list RED: divergent page-local key and routing helpers accepted mismatched optional product identity and invalid non-finite/fractional pagination. Shared contract tests and all three page integrations now use the same validation and normalization path.
- Product/group RED: the nominal three-product test rendered only CORN/SOYBEAN and synthetic facts. It now renders all three headings and exercises migrated CORN, SOYBEAN, and RICE quality facts together with all four Chinese groups.

## Round-three verification

- Backend: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home mvn verify` — BUILD SUCCESS; 119 tests, 0 failures/errors/skips; PostgreSQL validated and replayed all 16 migrations from empty and staged schemas; executable JAR built.
- Frontend: `npm run verify` — Prettier, ESLint, dependency-cruiser plus direct/transitive guard probes, 15 Vitest files / 84 tests, TypeScript, and Vite production build all passed.
- `git diff --check` passed in both repositories before the implementation commits. V1–V15 and frontend `.idea/` remained untouched. Probe files were cleaned, and no push was performed.

## Round-three commits

- Backend implementation: `1aeec1c fix(production): address round-three backend review`
- Frontend implementation: `4186cc0 fix(production): address round-three frontend review`
- This report is committed separately after both implementation hashes were known.

## Round-three hand-off

- All round-three findings have targeted regression coverage; a fourth independent review is the next gate.
- Task 9 still owns full authentication/authorization and auditing. Task 5 continues to use the authenticated servlet principal through `CurrentActor`.

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
