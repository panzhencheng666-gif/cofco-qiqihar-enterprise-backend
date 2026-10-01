# Independent risk regional authorization (stage B)

This contract applies only to the independent module. Main business shared reporting,
account roles, MFA, source export and assessment generation are unchanged.

## Session authority

`RISK_BUSINESS_SESSION_URL` must point to the trusted business `/api/v1/session/me`
endpoint. The independent service forwards only the session cookie and obtains
`subjectId`, `permissions`, boolean `rootAdministrator` and `regionCodes` from its
response. Actor, root and region headers are never authority. The main system
already expands effective region grants recursively; risk performs exact membership
only, without prefix matching or additional ancestry expansion.

Region codes must be strings of 6–12 ASCII digits, consistent with the existing
business region identifier format. Missing/null/empty region lists give non-root
no access (403 at the HTTP session filter; empty/absent results at the scoped SQL
boundary). Malformed arrays, elements or wildcard grants reject session validation
(503), including mixed valid/invalid lists. Only explicit JSON boolean `true` grants
root access; string or numeric truth values are rejected. A valid root session may
have no regions. Malformed claims are rejected even for root.

## Persisted assessment contract

The transitional risk-side region metadata is the top-level **string**
`risk.risk_assessment.evidence_snapshot.regionCode`. No region is inferred from
subject IDs, source IDs, nested evidence, request headers or feedback bodies.
All four repository operations require immutable `RiskRegionScope`. List filtering
happens before LIMIT. Detail, existence and INSERT ... SELECT feedback use the same
parameterized SQL predicate. Inaccessible and nonexistent IDs return the same 404
code/message, apart from independent request trace IDs. Duplicate feedback remains
409 only while the assessment is accessible. The insert independently rechecks
scope even if called without the service existence check.

This JSON contract is safe only for trusted, validated assessment producers that
assert a single region for the entire assessment and associated judgement/evidence.
There is no client assessment-write endpoint in this module. Existing evidence
without this metadata, including null/numeric/empty/malformed region values, stays
root-only. No backfill or guessed region is introduced. Cross-region assessments
must remain root-only; a future producer must not stamp one region on mixed-region
evidence. Source ingestion does not automatically create assessments or copy this
field. A dedicated region column and producer validation can replace this
transitional contract when assessment generation is designed; no migration is
needed for this bounded task.

## Global model boundary

No regional model isolation currently exists. Both model overview and manual
training requests therefore require the existing trusted root flag, enforced inside
`RiskModelOperationsService` before any repository call. Region-scoped users cannot
enqueue global training through these endpoints. No new role or permission is
created. Existing system scheduling is unchanged.

Raw training/scoring cases are available only through the separate machine-node
credential boundary, not through business sessions or headers claiming root.
The configured node token remains a global machine trust capability; it must not
be supplied to browser clients or regional users. This task does not narrow an
already privileged machine node or implement regional models.

## Source-fact ingress contract

`POST /api/v1/risk-intelligence/source-facts` remains machine-key ingress outside
the session filter. It requires both the existing `RISK_INGESTION_KEY` and explicit
`RISK_INGESTION_ALLOWED_REGIONS` (property `qiqihar.risk.ingestion-allowed-regions`).
The latter is a comma-separated exact allowlist; surrounding configuration-item
whitespace is trimmed. Empty/unset means deny all (403). Wildcards, malformed values
and empty items in a nonempty list fail application startup. Do not use wildcard or
assume a parent region authorizes its descendants.

The required field is `payload.regionCode`, a JSON string of 6–12 ASCII digits.
It is type-checked without coercion by `SourceFact`, exposed as a typed String
accessor, and checked against the configured allowlist by the ingestion service
**before find or insert**. Missing/null/numeric/wildcard/malformed values produce
400. Client region headers cannot supply, override or repair this field. The region
is persisted in the immutable payload and included in its canonical SHA-256 hash.
Changing it for the same source version conflicts; old hashes are not rewritten.
The existing hash still covers only the payload: changing `businessOccurredAt`
with the same source key and unchanged payload can return the previous receipt.
This pre-existing identity/content issue is deferred to the source-integration
task; it is not repaired by regional authorization.

Before later ingestion deployment, explicitly configure only the authorized regions
and verify the exporter obtains them from authoritative records. The allowlist
bounds the trusted machine key; it cannot prove a privileged exporter's factual
claim about a record. Actual source export and external ingestion remain separate
work. No training files or weights are stored in RDS by this change.

## Verification and deployment prerequisites

Use `source scripts/jdk21-env.sh` and the independent Maven POM. The new SQL test
uses only `RISK_SCOPE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:<port>/risk_scope_test`
with role `scope_test`. Provision a disposable local PostgreSQL cluster first.
The test resets only its dedicated test database's risk schema and uses clearly
synthetic fixtures/minimal tables; it is not a full migration/runtime test.
Unset `RISK_DB_URL` while running these tests to avoid activating legacy tests
against any existing deployment. The two legacy Spring integration tests are
intentionally skipped without that variable.

Deployment must separately verify session claims, configured ingress regions,
the existing risk-only database write boundary, and trusted provenance of any
assessment region metadata. Source tests and commit do not demonstrate deployment,
real IdP login, persisted production readback or browser acceptance. No deployment,
browser action, source export, external ingestion or production mutation was
performed for this task.
