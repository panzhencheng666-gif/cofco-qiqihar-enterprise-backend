package com.cofco.qiqihar.riskintelligence.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AiAssistantMigrationContractTest {
    @Test
    void createsPrivateAuditedKnowledgeAndShortLeasedAssistantQueues() throws Exception {
        String sql = Files.readString(Path.of(
                "../ops/risk-intelligence/migrations/V222__create_qiliang_ai_assistant.sql"));

        assertThat(sql)
                .contains("CREATE TABLE risk.ai_knowledge_document")
                .contains("object_key")
                .contains("access_scope")
                .contains("source_url")
                .contains("content_sha256")
                .contains("CREATE TABLE risk.ai_assistant_request")
                .contains("idempotency_key")
                .contains("UNIQUE (requested_by_subject,idempotency_key)")
                .contains("attempt_count BETWEEN 0 AND 3")
                .contains("lease_until")
                .contains("response_json")
                .contains("CREATE TABLE risk.ai_assistant_audit")
                .contains("BEFORE UPDATE OR DELETE ON risk.ai_assistant_audit")
                .contains("GRANT SELECT,INSERT,UPDATE ON risk.ai_assistant_request TO qiqihar_risk_runtime")
                .doesNotContain("GRANT ALL", "GRANT SELECT TO PUBLIC", "public-read");
    }
}
