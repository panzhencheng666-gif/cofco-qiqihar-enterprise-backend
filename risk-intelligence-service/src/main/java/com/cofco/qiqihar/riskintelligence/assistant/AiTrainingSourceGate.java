package com.cofco.qiqihar.riskintelligence.assistant;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A valid dataset format alone never grants the right to train on its sources. */
@Service
public class AiTrainingSourceGate {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AiTrainingSourceGate(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void requireGovernedSnapshot(UUID snapshotId) {
        String stored = jdbc.sql("""
                SELECT dataset_json::text FROM risk.expert_dataset_snapshot
                WHERE snapshot_id=:id
                """).param("id", snapshotId).query(String.class).optional().orElse(null);
        if (stored == null) denied();
        requireGovernedSources(json.readTree(stored));
    }

    public void requireGovernedSources(JsonNode dataset) {
        for (JsonNode source : dataset.path("sources")) {
            UUID id;
            try {
                id = UUID.fromString(source.path("sourceId").asText());
            } catch (IllegalArgumentException exception) {
                denied();
                return;
            }
            boolean eligible = jdbc.sql("""
                    SELECT status='APPROVED' AND use_scope='TRAINING_ALLOWED'
                        AND source_kind <> 'SEARCH_CANDIDATE'
                        AND approved_by_subject <> 'automated-source-verification'
                        AND content_sha256=:hash AND title=:title AND source_url=:url
                        AND license_code=:license AND rights_evidence=:evidence
                    FROM risk.ai_knowledge_document WHERE document_id=:id
                    """).param("id", id)
                    .param("hash", source.path("contentSha256").asText())
                    .param("title", source.path("title").asText())
                    .param("url", source.path("url").asText())
                    .param("license", source.path("license").asText())
                    .param("evidence", source.path("licenseEvidenceUrl").asText())
                    .query(Boolean.class).optional().orElse(false);
            if (!eligible) denied();
        }
    }

    private static void denied() {
        throw new RiskApiException(HttpStatus.CONFLICT, "AI_TRAINING_SOURCE_NOT_AUTHORIZED",
                "训练资料未在知识库核验使用权，或版本、授权证据不匹配");
    }
}
