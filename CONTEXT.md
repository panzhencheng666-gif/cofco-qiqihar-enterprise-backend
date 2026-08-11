# Grain Enterprise Business Platform

## Language

**Submission Context**:
The mandatory, auditable identity and location context captured with every production,
market, or logistics submission: reporter name and contact, surveyed party name and
contact, governed region or route context, survey date, and latitude/longitude. It is
business data, not a client-side display hint.
_Avoid_: form metadata, page metadata

**Surveyed Party**:
The customer, farmer, trader, enterprise, or logistics counterparty about whom a record
is reported. It is distinct from the authenticated reporter who creates the record.
_Avoid_: reporter, operator

**Evidence Photo**:
A private field image uploaded by the reporter and linked to one business record. A
submission cannot enter review unless it has at least one valid evidence photo; the
server owns its metadata, access control, integrity hash, and watermarked representation.
_Avoid_: attachment, display image
