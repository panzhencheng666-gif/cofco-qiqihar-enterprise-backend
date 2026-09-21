# Risk Intelligence Local Isolation Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy a separately managed local risk-intelligence backend that shares the existing PostgreSQL database while being technically restricted to writes inside the `risk` schema.

**Architecture:** A new Spring Boot process under `risk-intelligence-service/` owns risk-system runtime APIs and connects with a dedicated database role and connection pool. Existing business schemas remain the source of truth; a new additive migration removes cross-schema foreign-key coupling and stores immutable source snapshots inside `risk`, while the existing business backend remains unchanged.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC, Spring JDBC, PostgreSQL 17, Flyway migrations in the existing backend, JUnit 5, AssertJ, Bash, macOS launchd.

## Global Constraints

- The application entry remains `系统首页 → 应用中心 → 风险研判预警` and returns to the application center.
- The existing RDS instance and existing PostgreSQL database are shared; all risk-owned database objects stay in schema `risk`.
- Risk migrations must not alter, update, or delete objects or rows in existing business schemas.
- The risk runtime account may read explicitly granted business projections and may write only schema `risk`.
- New source snapshots retain source ID, source version, event time, ingestion time, payload hash, and payload.
- No model artifacts, LoRA weights, raw training files, or large logs are stored in PostgreSQL.
- Existing business frontend, backend, permissions, and workflows must continue to pass their current regression checks.
- Every implementation task follows RED → GREEN → REFACTOR and ends in a bounded commit.

---

### Task 1: Standalone Risk Backend and Database Boundary Guard

**Files:**
- Create: `risk-intelligence-service/pom.xml`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/RiskIntelligenceApplication.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/configuration/RiskDatabaseBoundary.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/configuration/RiskDatabaseBoundaryConfiguration.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/operations/BoundaryStatusController.java`
- Create: `risk-intelligence-service/src/main/resources/application.yml`
- Create: `risk-intelligence-service/src/test/java/com/cofco/qiqihar/riskintelligence/configuration/RiskDatabaseBoundaryTest.java`
- Create: `risk-intelligence-service/src/test/java/com/cofco/qiqihar/riskintelligence/operations/BoundaryStatusControllerTest.java`

**Interfaces:**
- Consumes: `RISK_DB_URL`, `RISK_DB_USERNAME`, `RISK_DB_PASSWORD`, `RISK_EXPECTED_DATABASE`, and `RISK_SERVER_PORT`.
- Produces: `RiskDatabaseBoundary.verify(String currentDatabase, String currentUser, Set<String> writableSchemas)` and `GET /api/v1/risk-intelligence/operations/boundary`.

- [ ] **Step 1: Write the failing boundary tests**

```java
@Test
void acceptsTheExistingDatabaseOnlyWhenRiskIsTheOnlyWritableSchema() {
    assertThatCode(() -> RiskDatabaseBoundary.verify(
            "qiqihar_enterprise_test", "risk_runtime", Set.of("risk"),
            "qiqihar_enterprise_test"))
            .doesNotThrowAnyException();
}

@Test
void rejectsWriteAccessToAnExistingBusinessSchema() {
    assertThatThrownBy(() -> RiskDatabaseBoundary.verify(
            "qiqihar_enterprise_test", "risk_runtime", Set.of("risk", "platform"),
            "qiqihar_enterprise_test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("platform");
}

@Test
void rejectsAConnectionToAnUnexpectedDatabase() {
    assertThatThrownBy(() -> RiskDatabaseBoundary.verify(
            "postgres", "risk_runtime", Set.of("risk"), "qiqihar_enterprise_test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unexpected risk database");
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
./scripts/mvn-jdk21.sh -f risk-intelligence-service/pom.xml \
  -Dtest=RiskDatabaseBoundaryTest test
```

Expected: compilation fails because `RiskDatabaseBoundary` does not exist.

- [ ] **Step 3: Implement the standalone application and guard**

The guard queries the current database, current user, and writable non-temporary schemas from `information_schema.role_schema_grants`, then refuses startup unless the database name equals `qiqihar.risk.expected-database` and the writable schema set is exactly `risk`.

```java
public record RiskDatabaseBoundary(
        String databaseName,
        String databaseUser,
        Set<String> writableSchemas,
        Instant verifiedAt) {

    public static void verify(
            String currentDatabase,
            String currentUser,
            Set<String> writableSchemas,
            String expectedDatabase) {
        if (!expectedDatabase.equals(currentDatabase)) {
            throw new IllegalStateException("Unexpected risk database: " + currentDatabase);
        }
        Set<String> unexpected = writableSchemas.stream()
                .filter(schema -> !schema.equals("risk"))
                .collect(Collectors.toUnmodifiableSet());
        if (!unexpected.isEmpty() || !writableSchemas.contains("risk")) {
            throw new IllegalStateException(
                    "Risk runtime write boundary violation: " + unexpected);
        }
        if (currentUser == null || currentUser.isBlank()) {
            throw new IllegalStateException("Risk database user is unavailable");
        }
    }
}
```

`application.yml` must set `spring.flyway.enabled: false`, because the existing backend's controlled Flyway history remains the only migration authority for the shared database. It must use only `RISK_*` environment variables and default to port `63184`.

- [ ] **Step 4: Expose the verified boundary as a real operations resource**

```java
@RestController
@RequestMapping("/api/v1/risk-intelligence/operations")
final class BoundaryStatusController {
    private final RiskDatabaseBoundary boundary;

    BoundaryStatusController(RiskDatabaseBoundary boundary) {
        this.boundary = boundary;
    }

    @GetMapping("/boundary")
    RiskDatabaseBoundary boundary() {
        return boundary;
    }
}
```

The response comes from the startup query; do not hard-code database, user, schema, or verification time.

- [ ] **Step 5: Run tests and package**

Run:

```bash
./scripts/mvn-jdk21.sh -f risk-intelligence-service/pom.xml test
./scripts/mvn-jdk21.sh -f risk-intelligence-service/pom.xml -DskipTests package
```

Expected: all service tests pass and `risk-intelligence-service/target/risk-intelligence-service-0.1.0-SNAPSHOT.jar` exists.

- [ ] **Step 6: Commit**

```bash
git add risk-intelligence-service
git commit -m "feat(risk): add isolated backend runtime"
```

### Task 2: Remove Cross-Schema Foreign-Key Coupling Without Losing Data

**Files:**
- Create: `src/main/resources/db/migration/V217__isolate_risk_schema_runtime.sql`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/risk/infrastructure/RiskSchemaIsolationMigrationContractTest.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/risk/infrastructure/RiskSchemaIsolationIntegrationTest.java`

**Interfaces:**
- Consumes: existing `risk` tables created by V214–V216.
- Produces: immutable `risk.source_fact_snapshot`, schema privilege boundary, and source-reference columns without cross-schema foreign keys.

- [ ] **Step 1: Write the failing migration contract test**

```java
@Test
void migrationOnlyChangesRiskSchemaAndRemovesBusinessForeignKeys() throws IOException {
    String sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V217__isolate_risk_schema_runtime.sql"));

    assertThat(sql)
            .contains("SET lock_timeout = '2s'")
            .contains("CREATE TABLE risk.source_fact_snapshot")
            .contains("source_record_id varchar(200) NOT NULL")
            .contains("source_version varchar(160) NOT NULL")
            .contains("payload_sha256 char(64) NOT NULL")
            .contains("REVOKE CREATE ON SCHEMA risk FROM PUBLIC")
            .doesNotContain("ALTER TABLE platform.")
            .doesNotContain("ALTER TABLE overview.")
            .doesNotContain("UPDATE platform.")
            .doesNotContain("UPDATE overview.")
            .doesNotContain("DELETE FROM platform.")
            .doesNotContain("DELETE FROM overview.");
}
```

- [ ] **Step 2: Run the test and confirm RED**

Run:

```bash
./scripts/mvn-jdk21.sh -Dtest=RiskSchemaIsolationMigrationContractTest test
```

Expected: failure because V217 does not exist.

- [ ] **Step 3: Write the additive migration**

The migration must:

```sql
SET lock_timeout = '2s';
SET statement_timeout = '30s';

REVOKE CREATE ON SCHEMA risk FROM PUBLIC;

CREATE TABLE risk.source_fact_snapshot (
    snapshot_id uuid PRIMARY KEY,
    source_system varchar(80) NOT NULL,
    source_record_type varchar(100) NOT NULL,
    source_record_id varchar(200) NOT NULL,
    source_version varchar(160) NOT NULL,
    business_occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    source_status varchar(20) NOT NULL DEFAULT 'CURRENT'
        CHECK (source_status IN ('CURRENT','CORRECTED','WITHDRAWN')),
    UNIQUE (source_system,source_record_type,source_record_id,source_version)
);

CREATE INDEX source_fact_snapshot_ingested_idx
    ON risk.source_fact_snapshot(ingested_at,snapshot_id);
```

Drop only the named V214 foreign-key constraints from `risk.inventory_source`, `risk.inventory_source_event`, `risk.inventory_movement`, and `risk.inventory_balance` that reference `overview.storage_facility` or `platform.product`. Preserve the identifier columns and add B-tree indexes where the removed foreign keys previously supplied lookup semantics. Add an immutable trigger that rejects `UPDATE` and `DELETE` on `risk.source_fact_snapshot`.

- [ ] **Step 4: Add integration assertions**

The integration test records pre-migration counts for existing `risk` tables, runs Flyway, verifies the counts are unchanged, verifies no constraint owned by schema `risk` references a non-`risk` schema, and proves a duplicate source version is rejected.

```sql
SELECT count(*)
FROM pg_constraint c
JOIN pg_class source_table ON source_table.oid=c.conrelid
JOIN pg_namespace source_schema ON source_schema.oid=source_table.relnamespace
JOIN pg_class target_table ON target_table.oid=c.confrelid
JOIN pg_namespace target_schema ON target_schema.oid=target_table.relnamespace
WHERE c.contype='f'
  AND source_schema.nspname='risk'
  AND target_schema.nspname<>'risk';
```

Expected value: `0`.

- [ ] **Step 5: Run contract and isolated PostgreSQL tests**

Run:

```bash
./scripts/mvn-jdk21.sh \
  -Dtest=RiskSchemaIsolationMigrationContractTest,RiskSchemaIsolationIntegrationTest test
```

Expected: 2 test classes pass and existing risk-row counts remain unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V217__isolate_risk_schema_runtime.sql \
  src/test/java/com/cofco/qiqihar/graintrade/risk/infrastructure/RiskSchemaIsolationMigrationContractTest.java \
  src/test/java/com/cofco/qiqihar/graintrade/risk/infrastructure/RiskSchemaIsolationIntegrationTest.java
git commit -m "feat(risk): isolate schema from business tables"
```

### Task 3: Dedicated Database Roles and Verifiable Least Privilege

**Files:**
- Create: `ops/risk-intelligence/create-risk-runtime-roles.sql`
- Create: `scripts/configure-risk-database-access.sh`
- Create: `scripts/verify-risk-database-boundary.sh`
- Create: `scripts/tests/verify-risk-database-boundary.test.sh`
- Create: `docs/operations/risk-intelligence-database-access.md`

**Interfaces:**
- Consumes: PostgreSQL administrator connection supplied only at execution time.
- Produces: `qiqihar_risk_flyway` and `qiqihar_risk_runtime` roles plus a machine-readable privilege verification report.

- [ ] **Step 1: Write the failing shell contract test**

```bash
assert_contains "CREATE ROLE qiqihar_risk_runtime"
assert_contains "GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime"
assert_contains "ALTER DEFAULT PRIVILEGES IN SCHEMA risk"
assert_not_contains "GRANT INSERT ON ALL TABLES IN SCHEMA platform"
assert_not_contains "GRANT UPDATE ON ALL TABLES IN SCHEMA overview"
```

- [ ] **Step 2: Run the shell test and confirm RED**

Run:

```bash
bash scripts/tests/verify-risk-database-boundary.test.sh
```

Expected: failure because the role script is absent.

- [ ] **Step 3: Implement idempotent role provisioning**

The SQL creates roles only when absent, never stores passwords in Git, and applies:

```sql
GRANT CONNECT ON DATABASE :"database_name" TO qiqihar_risk_runtime;
GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA risk TO qiqihar_risk_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA risk TO qiqihar_risk_runtime;
ALTER ROLE qiqihar_risk_runtime SET statement_timeout='15s';
ALTER ROLE qiqihar_risk_runtime SET lock_timeout='2s';
ALTER ROLE qiqihar_risk_runtime SET idle_in_transaction_session_timeout='30s';
```

Existing business schemas grant only the exact `SELECT` projections required by later connector plans. This task grants no write privilege outside `risk`.

- [ ] **Step 4: Implement verification against PostgreSQL catalogs**

`verify-risk-database-boundary.sh` exits non-zero if the runtime role owns or has `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`, `CREATE`, or `TEMP` outside `risk`. It prints each unexpected privilege before exiting.

- [ ] **Step 5: Run shell and live database verification**

Run:

```bash
bash scripts/tests/verify-risk-database-boundary.test.sh
RISK_DB_ADMIN_URL="$QIQIHAR_TEST_DB_URL" \
  bash scripts/verify-risk-database-boundary.sh
```

Expected: contract test passes; live report ends with `RISK_DATABASE_BOUNDARY_OK`.

- [ ] **Step 6: Commit**

```bash
git add ops/risk-intelligence scripts/configure-risk-database-access.sh \
  scripts/verify-risk-database-boundary.sh scripts/tests/verify-risk-database-boundary.test.sh \
  docs/operations/risk-intelligence-database-access.md
git commit -m "feat(risk): enforce dedicated database role"
```

### Task 4: Immutable Source Snapshot Ingestion

**Files:**
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/integration/SourceFact.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/integration/SourceFactRepository.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/integration/JdbcSourceFactRepository.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/integration/SourceFactIngestionService.java`
- Create: `risk-intelligence-service/src/main/java/com/cofco/qiqihar/riskintelligence/integration/SourceFactController.java`
- Create: `risk-intelligence-service/src/test/java/com/cofco/qiqihar/riskintelligence/integration/SourceFactIngestionServiceTest.java`
- Create: `risk-intelligence-service/src/test/java/com/cofco/qiqihar/riskintelligence/integration/JdbcSourceFactRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: versioned business facts delivered by the future read-only connector; phase-one acceptance uses a signed local administrative request.
- Produces: `POST /api/v1/risk-intelligence/source-facts` and immutable rows in `risk.source_fact_snapshot`.

- [ ] **Step 1: Write failing idempotency and hash tests**

```java
@Test
void persistsOneImmutableSnapshotPerSourceVersion() {
    SourceFact fact = new SourceFact(
            "enterprise", "BUSINESS_AUDIT", "event-1", "v1",
            Instant.parse("2026-09-21T01:00:00Z"), Map.of("actionCode", "SUBMITTED"));

    SourceFactReceipt first = service.ingest(fact);
    SourceFactReceipt repeated = service.ingest(fact);

    assertThat(repeated.snapshotId()).isEqualTo(first.snapshotId());
    assertThat(repeated.payloadSha256()).isEqualTo(first.payloadSha256());
    verify(repository, times(1)).insert(any());
}

@Test
void rejectsTheSameSourceVersionWithDifferentContent() {
    service.ingest(fact("v1", Map.of("value", 1)));
    assertThatThrownBy(() -> service.ingest(fact("v1", Map.of("value", 2))))
            .isInstanceOf(SourceVersionConflictException.class);
}
```

- [ ] **Step 2: Run focused tests and confirm RED**

Run:

```bash
./scripts/mvn-jdk21.sh -f risk-intelligence-service/pom.xml \
  -Dtest=SourceFactIngestionServiceTest test
```

Expected: compilation failure because ingestion types do not exist.

- [ ] **Step 3: Implement canonical hashing and idempotent storage**

Serialize payloads with deterministically ordered JSON keys, calculate lowercase SHA-256, and use the tuple `(source_system, source_record_type, source_record_id, source_version)` as the idempotency key. Repeated identical content returns the persisted receipt; repeated different content returns HTTP 409 and does not overwrite the first snapshot.

- [ ] **Step 4: Add database integration coverage**

Verify the runtime repository executes only `SELECT` and `INSERT` in schema `risk`, duplicate insert handling is deterministic, and direct `UPDATE`/`DELETE` fails through the database immutability trigger.

- [ ] **Step 5: Run service tests**

Run:

```bash
./scripts/mvn-jdk21.sh -f risk-intelligence-service/pom.xml test
```

Expected: all standalone service tests pass.

- [ ] **Step 6: Commit**

```bash
git add risk-intelligence-service
git commit -m "feat(risk): ingest immutable source snapshots"
```

### Task 5: Persistent Local Service and Non-Regression Acceptance

**Files:**
- Create: `ops/launchd/com.cofco.qiqihar.risk-intelligence.local.plist`
- Create: `scripts/run-risk-intelligence-launch-agent.sh`
- Create: `scripts/risk-intelligence-local.sh`
- Create: `scripts/healthcheck-risk-intelligence-local.sh`
- Create: `scripts/tests/risk-intelligence-local.test.sh`
- Modify: `docs/operations/risk-intelligence-database-access.md`

**Interfaces:**
- Consumes: packaged standalone JAR, restricted env file, shared PostgreSQL database, and launchd.
- Produces: restartable service on numeric loopback port `63184`, owned PID/log files, and health/boundary acceptance evidence.

- [ ] **Step 1: Write failing service-manager contract tests**

```bash
assert_plist_value "Label" "com.cofco.qiqihar.risk-intelligence.local"
assert_file_contains "ops/launchd/com.cofco.qiqihar.risk-intelligence.local.plist" "RunAtLoad"
assert_file_contains "ops/launchd/com.cofco.qiqihar.risk-intelligence.local.plist" "KeepAlive"
assert_file_contains "scripts/risk-intelligence-local.sh" "127.0.0.1:63184/actuator/health"
assert_file_contains "scripts/healthcheck-risk-intelligence-local.sh" "/api/v1/risk-intelligence/operations/boundary"
```

- [ ] **Step 2: Run the contract test and confirm RED**

Run:

```bash
bash scripts/tests/risk-intelligence-local.test.sh
```

Expected: failure because the plist and scripts do not exist.

- [ ] **Step 3: Implement safe local lifecycle scripts**

The installer copies the packaged JAR and configuration into a versioned runtime snapshot, stores secrets only in `${HOME}/.config/cofco-qiqihar-risk-intelligence/local-runtime.env` with mode `600`, writes logs under `${HOME}/Library/Logs/COFCO Qiqihar Risk Intelligence`, and uses PID ownership verification before stopping a process. It refuses to replace an unexpected path or kill an unowned listener.

- [ ] **Step 4: Install and verify the service**

Run:

```bash
./scripts/risk-intelligence-local.sh install
./scripts/risk-intelligence-local.sh status
./scripts/healthcheck-risk-intelligence-local.sh
```

Expected:

```text
RISK_INTELLIGENCE_HEALTH_OK
RISK_DATABASE_BOUNDARY_OK database=qiqihar_enterprise_test writableSchemas=risk
```

- [ ] **Step 5: Prove restart recovery**

Run:

```bash
./scripts/risk-intelligence-local.sh restart
./scripts/healthcheck-risk-intelligence-local.sh
```

Expected: launchd reports the service loaded and both checks pass after a new process ID is observed.

- [ ] **Step 6: Run proportionate regression checks**

Run:

```bash
./scripts/mvn-jdk21.sh \
  -Dtest=RiskSchemaIsolationMigrationContractTest,RiskSchemaIsolationIntegrationTest,\
RiskFoundationMigrationContractTest,RiskTrainingMigrationContractTest test
../risk-warning-web-phase1/node_modules/.bin/vitest run \
  ../risk-warning-web-phase1/src/risk/RiskWarningApplication.spec.tsx \
  ../risk-warning-web-phase1/src/risk/RiskModelCenter.spec.tsx
```

Expected: all targeted backend and risk-frontend tests pass. Then verify the existing backend health endpoint and application-center page still return HTTP 200 while the new risk process is running.

- [ ] **Step 7: Commit**

```bash
git add ops/launchd scripts docs/operations/risk-intelligence-database-access.md
git commit -m "feat(risk): install persistent local service"
```

## Phase Completion Gate

This phase is complete only when:

1. The standalone risk process starts independently and survives a managed restart.
2. Its live boundary endpoint proves the connected database, user, and exact writable schema set.
3. No foreign key owned by `risk` references a business schema.
4. Existing risk rows survive V217 unchanged.
5. A real source snapshot can be inserted, reread, deduplicated, and cannot be mutated.
6. Existing backend and application-center health remain unchanged while the new process runs.
7. All five tasks have clean commits and both repositories have no unintended changes.

The next plan begins only after this gate: migrate assessment/model APIs into the standalone service, then switch `/api/v1/risk/**` traffic away from the existing backend.
