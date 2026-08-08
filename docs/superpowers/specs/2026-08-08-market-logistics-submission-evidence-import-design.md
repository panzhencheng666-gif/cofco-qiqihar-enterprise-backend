# Market and Logistics Submission Evidence Import Design

## Scope and delivery order

This change aligns MARKET and then LOGISTICS with the production submission contract. MARKET is the first independently shippable vertical slice. LOGISTICS follows only after the MARKET contract, persistence, evidence authorization, import atomicity, and integration tests are green.

## Public submission metadata

MARKET keeps `MKT_OBJECT_TYPE` as a category and adds the required `MKT_SAMPLE_NAME` field for the concrete enterprise, store, sample point, or customer. The complete required MARKET metadata set is:

- `MKT_REPORTER_NAME`: reporter name.
- `MKT_REPORTER_PHONE`: reporter contact details.
- `MKT_SAMPLE_NAME`: concrete reported enterprise, store, sample point, or customer.
- `MKT_SAMPLE_CONTACT`: reported object or customer contact details.
- `MKT_SAMPLE_LATITUDE`: required latitude, -90 through 90.
- `MKT_SAMPLE_LONGITUDE`: required longitude, -180 through 180.

All text is bounded by the existing 500 Unicode-code-point rule. Coordinates use plain-decimal lexical validation and the metadata-defined precision and scale. Values persist as MARKET extension core values and are returned by detail and list projections.

LOGISTICS will use a separate `LOG_*` metadata vocabulary rather than overloading MARKET codes. Its route endpoints may span multiple regions, so authorization must check every associated route region.

## Private evidence

New MARKET and LOGISTICS records require one through five unique staged evidence photo IDs owned by the current subject. The existing private evidence store remains authoritative for original bytes, server-watermarked bytes, hashes, capture time, coordinates, ownership, and attachment state.

Evidence attachment APIs are explicit per domain. Callers cannot supply an arbitrary domain string. A staged photo is readable only by its uploader. Once attached, the evidence row inherits the business region used for `BUSINESS_READ` authorization. MARKET uses its single record region. LOGISTICS must preserve all route-region authorization requirements before exposing content.

Record creation, evidence availability validation, business insert, evidence attachment, and audit recording share one transaction. Any failure leaves no business record and no attached evidence.

## CSV and XLSX imports

Each domain owns a template and row-to-draft adapter while reusing the bounded CSV parser, bounded hardened XLSX parser, import reservation repository, and import job projection.

MARKET import rows contain the typed MARKET core values, all required common metadata, one `evidencePhotoId`, and no dynamic facts in the initial template. The template is intentionally narrow and covers the required price-composition fields already accepted by the MARKET command contract. Dynamic facts remain available through the normal record API and can be added to a later versioned template.

Import behavior is atomic per file:

1. Reserve `Idempotency-Key` for subject and domain using the original content SHA-256.
2. Parse CSV or XLSX into one canonical table.
3. Validate headers, every row, regional permission, master-data applicability, metadata, decimal contracts, and staged evidence availability without writes.
4. If any row fails, persist row errors and mark otherwise valid rows `NOT_IMPORTED_ATOMIC_BATCH`; write zero business records and attach zero photos.
5. If all rows pass, create every record and attach every photo in one transaction, then complete the job and audit it.

Same subject, domain, key, and digest returns the existing job. The same key with another digest returns the stable conflict contract. Error exports contain only user-safe row codes and messages.

## API contracts

- MARKET create accepts `evidencePhotoIds`; detail returns bounded evidence metadata, never original bytes or internal storage details.
- `GET /api/v1/imports/market/template` returns the versioned CSV header.
- `POST /api/v1/imports/market` accepts `.csv` and `.xlsx` with `Idempotency-Key`.
- `POST /api/v1/imports/market/{jobId}/retries` retries failed rows through the same atomic validation pipeline.
- `GET /api/v1/imports/market/{jobId}/errors` returns the user-safe CSV error file.

LOGISTICS will expose equivalent domain-specific import endpoints after the MARKET slice is accepted.

## Error and security behavior

Client errors use stable `INVALID_MARKET_RECORD`, `INVALID_EVIDENCE_PHOTO`, `EVIDENCE_PHOTO_NOT_FOUND`, `EVIDENCE_PHOTO_NOT_AVAILABLE`, `INVALID_IMPORT_*`, and import idempotency conflict codes. Database, ZIP/XML, image decoder, stack trace, and internal exception messages are never returned to users.

All reads and writes retain existing permission and region checks. Import pre-validation uses `BUSINESS_IMPORT` for every distinct row region, while record creation uses the domain's existing creation permission and audit path.

## Test strategy

Tests use the protected PostgreSQL test database and real HTTP controllers. MARKET coverage proves:

- `MKT_SAMPLE_NAME` and the two distinct contact fields are required and persisted.
- one through five owned photos attach atomically; unavailable photos cause zero writes.
- staged and attached evidence respect private and region authorization.
- CSV and XLSX success, same-key replay, mixed-row atomic rollback, row error export, and no attachment side effects.
- approved MARKET records remain readable by existing MARKET list/detail and overview/analysis consumers.
- Spring Modulith boundaries, full `mvn verify`, and V1-to-latest Flyway replay stay green.

LOGISTICS receives the same evidence and import proof plus multi-region authorization tests in its subsequent slice.
