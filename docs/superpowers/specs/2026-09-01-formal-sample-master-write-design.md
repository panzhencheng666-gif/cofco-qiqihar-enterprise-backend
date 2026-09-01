# Formal Sample Point Master Write Design

## Scope

Add Backend-only create and update operations for formal sample point master data. The change does not write period observations, annual-network membership, approval state machines, imports, or design-sample data.

## Data model

`registry.sample_point` remains the governed place identity and sole optimistic-lock owner. A one-to-one `registry.formal_sample_point_profile` stores only stable formal-sample attributes:

- object type
- address

Existing sample points remain readable with a null profile. Their first successful update creates the profile. Changing object type replaces the single profile classification and does not alter historical period observations.
Updating stable master fields preserves the point's existing approval and effective-period lifecycle state.

## API

- `POST /api/v1/formal-sample-points` returns `201 Created`, a `Location` header, and the persisted view.
- `PUT /api/v1/formal-sample-points/{id}` requires `expectedVersion` in the JSON body and returns the persisted view.
- Both accept `canonicalName`, `regionCode`, `address`, `longitude`, `latitude`, and `objectTypeCode`.

The existing list, detail, and governed delete endpoints remain compatible. Views add nullable profile fields so legacy rows are not broken.

## Governance and transaction boundary

Writes require `FORMAL_SAMPLE_MANAGE` for every affected region. Create persists an approved, valid `SURVEY_SITE`; update checks both the old and new regions. Each transaction validates the region boundary and object type, locks the coordinate key, writes the point and profile, requeries the authoritative view, and appends audit/outbox data. Any failure rolls back all writes.

No annual membership is created or modified. Delete relies on the profile foreign key cascade so no profile residue remains.

## Errors

- malformed or out-of-range values: `INVALID_FORMAL_SAMPLE_POINT`
- missing boundary data: `ADMIN_BOUNDARY_UNAVAILABLE`
- coordinate outside region: `COORDINATE_OUTSIDE_REGION`
- occupied coordinate: existing `SAMPLE_POINT_COORDINATE_OCCUPIED`
- stale update: `FORMAL_SAMPLE_POINT_VERSION_CONFLICT`
- relational conflict: `FORMAL_SAMPLE_POINT_CONFLICT`
