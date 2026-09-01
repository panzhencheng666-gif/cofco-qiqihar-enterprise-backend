# Formal Sample Backend Field Unification Design

## Scope

This change closes two Backend gaps without creating a parallel formal-sample model:

1. The existing production, market, and logistics definitions remain the authoritative source for labels, required flags, units, options, numeric precision, DTO parsing, persistence, hydration, and requery. Formal-sample observation writes keep calling those same services and create official, period-governed records directly.
2. Formal sample-point deletion becomes an explicit governed operation with its own permission, optimistic version check, region authorization, network-membership conflict, transactional reference cleanup, immutable audit, outbox, and SSE evidence.

Year-independent design sample points remain separate coordinate reference data. No formal write reads `platform.design_sample_*` or `registry.village_design_sample_point`.

## Formal market object applicability

Current formal definitions already give traders purchase and sale prices and give processors, breeding factories, and feed mills purchase-side fields while excluding sale price. A new forward migration adds `AGRICULTURAL_INPUT_STORE` to the formal market master data for all supported products and mounts its own fields:

- seed sales volume, decimal, kilograms, non-negative;
- seed retail price, decimal, yuan per kilogram, non-negative;
- supply status, governed enum;
- planting-intention trend, governed enum.

Generic grain prices and price components are excluded for this object type. Its record uses an observation direction and stores non-applicable generic price columns as SQL `NULL`. Repository replacement semantics delete prior fact and extension rows before inserting only the selected type's applicable values, so a type switch cannot leave old values behind.

## Formal sample-point management read and delete

`GET /api/v1/formal-sample-points` and `GET /api/v1/formal-sample-points/{id}` expose authorized formal survey-site identity, version, annual observation count, and network-membership count. They do not expose design sample points.

`DELETE /api/v1/formal-sample-points/{id}?expectedVersion=...` requires `FORMAL_SAMPLE_DELETE`, a matching region scope, and the current sample-point version. Any sample-network membership returns a conflict. A database function locks the sample, rechecks the version, region, effective actor permission, and actor region grant, then removes mutable business references and the sample identity atomically. Durable supply lineage, import rows, or attached evidence block deletion instead of becoming orphaned history. Resolution history keeps its snapshot while its nullable target reference is cleared by the same guarded database operation. Any unexpected reference failure rolls back the whole deletion.

The governed database function writes `FORMAL_SAMPLE_POINT_DELETED` to the immutable audit and outbox in the deletion transaction. Its detail includes the locked region code so the existing SSE path can emit the deletion event even though runtime has no direct sample-point delete privilege.

## Deliberate exclusions

- No Web or Frontend changes.
- No new review, pending, completion, publish, or import-task workflow.
- No removal of existing business-period or sample-network governance.
- No automatic removal of sample-network membership.
- No reuse of design-sample runtime tables.
- No production or shared-database operations.

## Verification

Focused integration tests cover definition metadata, create/requery/edit/update/requery, type-switch residue cleanup, formal-observation reuse of the same definition chain, permission, optimistic concurrency, coordinate guard preservation, network conflict, rollback, audit/outbox, physical zero residue, and migration replay on the isolated task database. The repository's required Maven/JDK 21 verify and migration gates remain the final local evidence.
