package com.cofco.qiqihar.riskintelligence.assistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Uses a disposable PostgreSQL database; never points at a business database. */
@EnabledIfEnvironmentVariable(named = "RISK_KNOWLEDGE_GATE_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/risk_knowledge_gate_isolated_test")
class AiTrainingSourceGateIntegrationTest {
    @Test
    void automatedApprovalCannotAuthorizeTrainingButReviewedSourceCan() {
        var jdbc = JdbcClient.create(new DriverManagerDataSource(System.getenv("RISK_KNOWLEDGE_GATE_URL")));
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS risk").update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS risk.ai_knowledge_document (
                  document_id uuid PRIMARY KEY, status text NOT NULL, use_scope text NOT NULL,
                  source_kind text NOT NULL, approved_by_subject text NOT NULL,
                  content_sha256 text NOT NULL, title text NOT NULL, source_url text NOT NULL,
                  license_code text NOT NULL, rights_evidence text NOT NULL)
                """).update();
        var gate = new AiTrainingSourceGate(jdbc, new ObjectMapper());
        UUID automatedId = UUID.randomUUID();
        UUID reviewedId = UUID.randomUUID();
        try {
            insert(jdbc, automatedId, "automated-source-verification");
            insert(jdbc, reviewedId, "human-reviewer");
            assertThatThrownBy(() -> gate.requireGovernedSources(dataset(automatedId)))
                    .isInstanceOf(RiskApiException.class);
            gate.requireGovernedSources(dataset(reviewedId));
        } finally {
            jdbc.sql("DELETE FROM risk.ai_knowledge_document WHERE document_id IN (:automated,:reviewed)")
                    .param("automated", automatedId).param("reviewed", reviewedId).update();
        }
    }

    private static void insert(JdbcClient jdbc, UUID id, String approver) {
        jdbc.sql("""
                INSERT INTO risk.ai_knowledge_document (
                  document_id,status,use_scope,source_kind,approved_by_subject,
                  content_sha256,title,source_url,license_code,rights_evidence)
                VALUES (:id,'APPROVED','TRAINING_ALLOWED','OFFICIAL',:approver,
                  :hash,'Test source','https://example.org/source','CC0-1.0','https://example.org/license')
                """).param("id", id).param("approver", approver)
                .param("hash", "a".repeat(64)).update();
    }

    private static tools.jackson.databind.JsonNode dataset(UUID id) {
        return new ObjectMapper().createObjectNode().set("sources",
                new ObjectMapper().createArrayNode().add(new ObjectMapper().createObjectNode()
                        .put("sourceId", id.toString()).put("contentSha256", "a".repeat(64))
                        .put("title", "Test source").put("url", "https://example.org/source")
                        .put("license", "CC0-1.0")
                        .put("licenseEvidenceUrl", "https://example.org/license")));
    }
}
