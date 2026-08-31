# Formal Sample Backend Field Unification Implementation Plan

> Execute in this feature worktree only. Keep the isolated PostgreSQL port and preserve the worktree after PR creation.

## 1. Lock the contract with failing tests

- Extend market REST integration coverage for trader purchase/sale metadata, processor purchase-only metadata, agricultural-input-store fields, create/requery/update/type-switch/requery, and direct database residue checks.
- Add formal sample-point REST integration coverage for authorized reads, stale and unauthorized deletion, region isolation, network and durable-history conflicts, transactional rollback, successful physical deletion, audit/outbox, and zero mutable references.
- Extend formal observation integration coverage to prove it consumes the same agricultural-input-store definition and persists only applicable values.
- Run only the named tests and retain the expected RED evidence.

## 2. Add the forward migration

- Add `V161__unify_formal_sample_fields_and_govern_deletion.sql`.
- Seed formal agricultural-input-store master data and options independently of the design-sample tables.
- Add observation-only market storage constraints with nullable non-applicable price components.
- Add `FORMAL_SAMPLE_DELETE` and assign it only to the system administrator role.
- Make resolution target references nullable on sample deletion and add a guarded security-definer deletion function which blocks network membership and deletes all mutable references atomically.

## 3. Update the shared market normalization chain

- Teach the market aggregate and parser about observation-only records.
- Keep existing price-bearing object behavior unchanged.
- Keep definition query, validation, persistence, hydration, and formal-observation writes on the same repository metadata.
- Verify replacement deletes obsolete fact and extension rows before inserting applicable current values.

## 4. Add formal sample-point read/delete application flow

- Add a focused `formalsamplepoint` application/repository/controller module.
- Scope reads by existing authorized regions.
- Require region-scoped delete permission and expected version.
- Call the database deletion function and translate not-found, stale, and network conflicts.
- Recheck actor permission and region after locking, revoke direct runtime deletion, and record the immutable audit and outbox event inside the governed database transaction.

## 5. Verify and publish

- Re-run focused tests, then migration replay and the repository's JDK 21 Maven verify gate with the isolated database.
- Review the diff for scope and security boundaries.
- Commit once the tree is clean, push the feature branch, create a PR without merging, and wait for exactly one native CI run for the current head.
