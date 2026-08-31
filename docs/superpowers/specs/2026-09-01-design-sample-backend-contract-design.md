# Design Sample Backend Contract Design

## Goal

Close the Backend-only contract for year-independent design sample points without changing the approved V157 field catalog, the V159 registry, the V160 Qiqihar boundaries, formal sample-network governance, or overview behavior.

## Authoritative model

- `platform.design_sample_*` from V157 remains the only field-definition source. No new object types, fields, units, options, ranges, or requiredness are invented.
- `platform.design_sample_point` from V159 remains the master store. It has no `surveyYear`, workflow state, review state, publication state, or import-task relationship.
- The existing V157 applicability matrix remains authoritative: traders support purchase and sale prices; processors, farms, and feed mills use their applicable purchase fields; agricultural-input stores use the four V157 agricultural-input fields.
- Create and update remain full replacement writes for `values_json`. Switching context therefore persists only fields valid for the new context; stale fields are rejected rather than silently retained.

## Application flow

`DesignSampleMetadataService` will expose one internal validation-and-normalization operation. The public pure-validation endpoint will continue to return field states without writing. Create and update will consume the same operation and persist its normalized map, so field type, precision, scale, enum, range, requiredness, whitespace, and JSON representation cannot drift between the two write paths.

Decimal inputs accepted as JSON strings or numbers will be persisted as JSON numbers. Text values will be trimmed after the V157 length rules are applied. Dates, enums, UUIDs, unknown/null values, and read-only fields retain the current fail-closed rules.

The point endpoint will add `GET /api/v1/design-sample-points/{id}`. It re-queries the committed master row and applies the caller's current read scope to the row's authoritative region before returning it.

## Transaction and event boundary

Create, update, and delete retain their existing Spring transaction. Permission, metadata, boundary, coordinate, uniqueness, version, and reference failures leave no point/audit/outbox residue. A successful write records the existing technical audit and outbox event in the same transaction; the generic business-event stream continues to deliver those region-scoped events.

No DRAFT, PENDING, APPROVE, REJECT, PUBLISH, work-item, completed-item, or import-task flow is added to the design-sample module.

## Verification

Focused tests will prove:

1. create -> authoritative GET -> agricultural-input values persisted in canonical JSON;
2. update to trader -> authoritative GET -> purchase and sale prices present and agricultural-input fields absent;
3. an invalid type switch rolls back without changing the row, audit, or outbox;
4. scoped read permission is enforced;
5. delete removes the row while leaving the three technical audit/outbox events;
6. existing V159/V160 upgrade replay and generic SSE delivery contracts still pass;
7. JDK 21 `mvn -B -ntp verify` and `git diff --check` pass before push.
