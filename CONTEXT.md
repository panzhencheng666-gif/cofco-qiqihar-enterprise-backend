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

**Approved Fact**:
A business fact from an approved record whose survey period is confirmed and whose
subject identity, version, unit, uniqueness, and region authorization have passed the
domain gates. Missing values remain missing and are never converted to zero.
_Avoid_: approved input, adopted value

**Approved Fact Snapshot**:
An immutable read model that fixes the approved fact versions, product, governed region,
survey period, cutoff time, and calculation methodology used by production analysis,
market analysis, and observable supply balance.
_Avoid_: dashboard cache, UI aggregate

**Surveyed Coverage**:
The governed regions, unique surveyed parties, and approved records actually included
in an approved fact snapshot. It must not be described as full regional coverage unless
an independently validated coverage policy proves that claim.
_Avoid_: total region by default

**Observable Supply Balance**:
A read-only balance calculated only from quantities available in the current production,
market, and logistics survey contracts. Market trades explain activity but are not added
to the quantity identity when they may duplicate production sales or logistics flows.
_Avoid_: manual supply account, adopted balance

**Production Source Balance**:
The reconciliation of opening inventory plus estimated output against sales, self-use,
and reported ending inventory for the approved production facts in scope.
_Avoid_: inventory adjustment

**Inferred Other Absorption**:
The residual required to close the observable regional quantity identity after opening
inventory, estimated output, inflow, self-use, outflow, and ending inventory are applied.
It is system-derived, not a submission field and not a claim about a specific use class.
_Avoid_: confirmed consumption, approved loss

**Analysis Result Version**:
A stable version derived from the approved fact versions, calculation methodology,
scope, and cutoff time. The same scope and inputs must return the same version to every
direct consumer.
_Avoid_: page version, refresh token
