package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "RISK_ASSESSMENT_TEST_ALLOW_RESET", matches = "isolated-only")
class MarketRuleAssessmentProcessorIntegrationTest {
    @Test
    void activeRuleReplaysEveryFactOnceAndNeverInventsAWarningWithoutARule() throws Exception {
        String url = System.getenv("RISK_ASSESSMENT_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/risk_assessment_isolated_test")) {
            throw new IllegalStateException("A dedicated loopback risk_assessment_isolated_test database is required");
        }
        var dataSource = new DriverManagerDataSource(url, "postgres", "");
        var jdbc = JdbcClient.create(dataSource);
        var json = new ObjectMapper();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS risk CASCADE");
            statement.execute("CREATE SCHEMA risk");
            statement.execute("CREATE TABLE risk.risk_rule_set_version (rule_set_id uuid NOT NULL,version integer NOT NULL,domain_code text NOT NULL,status_code text NOT NULL,effective_from timestamptz,effective_to timestamptz,scope_definition jsonb NOT NULL,rule_definition jsonb NOT NULL,definition_sha256 char(64) NOT NULL,PRIMARY KEY(rule_set_id,version))");
            statement.execute("CREATE TABLE risk.source_fact_snapshot (snapshot_id uuid PRIMARY KEY,source_system text NOT NULL,source_record_type text NOT NULL,source_record_id text NOT NULL,source_version text NOT NULL,ingested_at timestamptz NOT NULL,payload_sha256 char(64) NOT NULL,payload jsonb NOT NULL,source_status text NOT NULL)");
            statement.execute("CREATE TABLE risk.risk_assessment (assessment_id uuid PRIMARY KEY,domain_code text NOT NULL,subject_type text NOT NULL,subject_id text NOT NULL,source_event_id uuid,rule_set_id uuid NOT NULL,rule_set_version integer NOT NULL,evaluation_mode text NOT NULL,risk_level text NOT NULL,reason_codes varchar(80)[] NOT NULL,evidence_snapshot jsonb NOT NULL,evaluated_at timestamptz NOT NULL,evaluation_duration_ms integer NOT NULL)");
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_enterprise_runtime') THEN CREATE ROLE qiqihar_enterprise_runtime; END IF; END $$");
            statement.execute(java.nio.file.Files.readString(java.nio.file.Path.of(
                    "../ops/risk-intelligence/migrations/V225__track_market_rule_assessments.sql")));
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_risk_runtime') THEN CREATE ROLE qiqihar_risk_runtime NOLOGIN; END IF; IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='qiqihar_risk_runtime_login') THEN CREATE ROLE qiqihar_risk_runtime_login LOGIN; END IF; END $$");
            statement.execute("GRANT qiqihar_risk_runtime TO qiqihar_risk_runtime_login");
            statement.execute("GRANT USAGE ON SCHEMA risk TO qiqihar_risk_runtime");
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA risk TO qiqihar_risk_runtime");
            statement.execute("GRANT INSERT ON risk.risk_assessment,risk.market_rule_assessment_evaluation TO qiqihar_risk_runtime");
        }
        var runtimeDataSource = new DriverManagerDataSource(url, "qiqihar_risk_runtime_login", "");
        var processor = new MarketRuleAssessmentProcessor(JdbcClient.create(runtimeDataSource), json,
                new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource)),
                Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneOffset.UTC));
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000020");
        snapshot(jdbc, first, "3200");
        snapshot(jdbc, second, "2900");
        processor.poll();
        assertThat(count(jdbc, "risk.risk_assessment")).isZero();
        assertThat(count(jdbc, "risk.market_rule_assessment_evaluation")).isZero();

        UUID ruleId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO risk.risk_rule_set_version(
                  rule_set_id,version,domain_code,status_code,effective_from,
                  scope_definition,rule_definition,definition_sha256)
                VALUES(:id,1,'MARKET','ACTIVE','2026-09-24T00:00:00Z',
                  '{"sourceRecordType":"MARKET_RECORD"}'::jsonb,CAST(:definition AS jsonb),:hash)
                """)
                .param("id", ruleId)
                .param("definition", """
                        {"schemaVersion":1,"field":"actualTradePrice","operator":"GT",
                         "threshold":"3000","riskLevel":"LOW",
                         "reasonCode":"PRICE_ABOVE_CONFIGURED_LIMIT"}
                        """)
                .param("hash", "a".repeat(64)).update();
        processor.poll();
        assertThat(count(jdbc, "risk.market_rule_assessment_evaluation")).isEqualTo(2);
        assertThat(count(jdbc, "risk.risk_assessment")).isEqualTo(1);
        String evidence = jdbc.sql("SELECT evidence_snapshot::text FROM risk.risk_assessment")
                .query(String.class).single();
        assertThat(evidence).contains(first.toString(), "3200", "actualTradePrice", "230221");

        processor.poll();
        assertThat(count(jdbc, "risk.market_rule_assessment_evaluation")).isEqualTo(2);
        assertThat(count(jdbc, "risk.risk_assessment")).isEqualTo(1);
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000005");
        snapshot(jdbc, third, "3300");
        processor.poll();
        assertThat(count(jdbc, "risk.market_rule_assessment_evaluation")).isEqualTo(3);
        assertThat(count(jdbc, "risk.risk_assessment")).isEqualTo(2);
    }

    private static void snapshot(JdbcClient jdbc, UUID id, String price) {
        jdbc.sql("""
                INSERT INTO risk.source_fact_snapshot(
                  snapshot_id,source_system,source_record_type,source_record_id,source_version,
                  ingested_at,payload_sha256,payload,source_status)
                VALUES(:id,'QIQIHAR_ENTERPRISE','MARKET_RECORD',:record,'1',
                  '2026-09-25T05:00:00Z',:hash,CAST(:payload AS jsonb),'CURRENT')
                """)
                .param("id", id).param("record", id.toString()).param("hash", "b".repeat(64))
                .param("payload", "{\"regionCode\":\"230221\",\"productCode\":\"CORN\",\"statusCode\":\"APPROVED\",\"actualTradePrice\":\"" + price + "\"}")
                .update();
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
