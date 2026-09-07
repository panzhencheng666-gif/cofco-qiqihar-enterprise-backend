# Design sample map-boundary consistency implementation plan

Goal: preserve original coordinates and require design-reference points to lie inside the selected region as displayed on the map, per the user's explicit clarification on 2026-09-07.

Evidence: 34 of 145 outside-validation rows are inside the same region in administrative_boundary_render. Current validation reads administrative_boundary instead. The abandoned unconditional-write draft is archived outside the repository and is not published.

- [x] Add isolated regression: a point inside render geometry but outside the old validation geometry must save and requery unchanged; a point outside render geometry must still fail.
- [x] Make service repository and database design-reference containment trigger use the same administrative_boundary_render.geo_json boundary. Add forward-only V178; retain numeric, permission, audit and foreign-key checks.
- [x] Verify focused CRUD/import tests and migration constraints in a disposable protected test DB. Do not modify original workbook or real business data.
- [x] Record checkpoint and release boundaries. No claim of official cartographic accuracy or full 145-row correction: the user confirmed a source-row error; the remaining source/CRS inconsistencies still require provenance.

Scope: the reported design-reference import/CRUD path. Formal monitoring registry, source coordinate conversions and global boundary replacement require separate evidence; never fabricate a boundary or move original points.

Validation: 23 focused CRUD/import/upgrade checks passed together in a disposable PostGIS database. The new boundary regression failed before the fix. An upgrade-fixture effective-time failure was reproduced twice; fixture timing now uses an already-effective timestamp, without changing production governance. Publication and remote CI remain pending.
