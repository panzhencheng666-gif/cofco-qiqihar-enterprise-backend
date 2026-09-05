# Inline formal-sample location updates

`POST /api/v1/formal-sample-observations/observations` accepts optional
`sampleLocation` alongside its existing `domain`, `samplePointId`, `productCode`,
`observedAt`, and `payload` fields:

```json
{
  "sampleLocation": {
    "expectedVersion": 2,
    "regionCode": "230221",
    "longitude": "123.2345678",
    "latitude": "47.3456789"
  }
}
```

- `samplePointId` remains the authoritative identity. A location update never creates a sample.
- The location writer changes only region, governed geometry, version and update metadata;
  it does not overwrite name, object type, address, maintainer or lifecycle dates.
- Location editing requires `FORMAL_SAMPLE_MANAGE` in both original and destination regions.
  Observation permissions and maintainer checks continue to apply.
- Required fields, seven-decimal coordinate limits, administrative boundaries, coordinate
  occupancy, maintainer scope and optimistic version are validated server-side.
- Location, observation, audit, outbox and idempotency receipt commit in one transaction.
  Any failure rolls all of them back. Replaying the same key/request returns the original
  result; changing the location with the same key conflicts.
- Without `sampleLocation`, existing request hashes and observation-only behavior remain compatible.
- Master changes publish `FORMAL_SAMPLE_POINT_UPDATED` with both old/new region codes.
  Observation saves publish the existing domain/product event. Consumers requery authoritative data.
- Master coordinates retain up to seven decimals. Logistics observation snapshots retain their
  existing six-decimal field/storage contract; the inline editor and governed map use master coordinates.
- Logistics observations now update the existing approved sample/product/period record, as market
  and production already do. Period metadata is explicitly restored after the compatibility-date
  trigger; this avoids leaving a saved record in `PENDING_GOVERNANCE`. Receipt snapshots retain history.

Deploy the backend contract before the Web client. Do not publish the new Web build against an
older backend that ignores `sampleLocation`. Source-level tests do not constitute acceptance of
the running local stack; browser/database/SSE verification is a separate release gate.
