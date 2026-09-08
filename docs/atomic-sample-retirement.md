# Atomic retirement of the current sample collection

Retirement operates on `sample_point_id`, independently of product observations.
The full candidate collection is read through `CurrentOverviewSamplePointReader`
with all products and categories, the database transaction's Shanghai business
date, and the authenticated account's authorized regions. Registry rows that are
absent from this authoritative collection are not candidates. Client pagination,
page filters and client-provided IDs cannot change this collection.

## Contract

- `POST /api/v1/formal-sample-points/retirement-previews` persists a ten-minute
  preview: owner, work unit, authorized region set, business date, full ordered
  sample identities, names, regions and versions.
- `GET /api/v1/formal-sample-points/retirement-previews/{id}` returns the preview
  or its durable execution receipt to its owner, subject to current permission
  and region checks.
- `PUT /api/v1/formal-sample-points/retirement-previews/{id}/execution` accepts
  `{ "reason": "..." }`. The preview ID is the durable idempotency identity.
  Repeating the same normalized reason returns the original receipt. Reusing a
  completed preview with a different reason returns a conflict.

Both BUSINESS_READ and FORMAL_SAMPLE_DELETE are required. Before first execution,
expiry, Shanghai date, work unit, authorization set, authoritative membership and
sample versions are revalidated. A changed preview returns
`RETIREMENT_PREVIEW_STALE` without retirement. The operation uses a serializable
transaction and bounded retries in the controller, outside the transaction.
Exhausted serialization/lock retries return `RETIREMENT_RETRY_REQUIRED`; callers
must retain the same preview ID and reason.

Every candidate uses the existing sample lifecycle service and database function.
Sample state, membership changes, reasons/actors, audit events, outbox events and
execution receipt commit together. Failure of any write rolls back the batch.
Existing business records remain intact. Each sample emits the existing
`FORMAL_SAMPLE_POINT_RETIRED` event, used by the existing authorized notification
and client refresh paths. Retirement dates and audit years use the same database
transaction timestamp in Asia/Shanghai as the stored `retired_at`, including
transactions crossing midnight.

## Web behavior and verification

The entry states its full account scope and provides the complete candidate list.
A reason and explicit confirmation are required. After execution, the client
requeries the ledger. Lost responses trigger receipt lookup; unresolved results
retain the same request identity. Definitive rejection permits a fresh preview.
A failed ledger refresh does not change a successful retirement receipt.

Focused integration tests use only the protected isolated test database. They
cover excluded master rows, more than one page, multiple products sharing one
identity, stale membership/versions/expiry, owner isolation, atomic audit failure,
concurrent duplicate confirmation, notification scope and retirement-year
consistency. Runtime publication and real browser acceptance are separate gates;
these tests never retire real business samples.
