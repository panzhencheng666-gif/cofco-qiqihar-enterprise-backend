package com.cofco.qiqihar.riskintelligence.integration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Replays trusted market snapshots through ACTIVE rules without inventing a rule or an alert. */
@Component
@ConditionalOnProperty(name = "qiqihar.risk.integration.market.enabled", havingValue = "true")
class MarketRuleAssessmentProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(MarketRuleAssessmentProcessor.class);
    private static final int BATCH_SIZE = 100;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;

    MarketRuleAssessmentProcessor(JdbcClient jdbc, ObjectMapper json,
            TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${qiqihar.risk.assessment.poll-delay:PT1M}")
    void poll() {
        Instant now = clock.instant();
        List<ActiveRule> rules = jdbc.sql("""
                SELECT rule_set_id,version,scope_definition::text,rule_definition::text,definition_sha256
                FROM risk.risk_rule_set_version
                WHERE domain_code='MARKET' AND status_code='ACTIVE'
                  AND effective_from<=:now AND (effective_to IS NULL OR effective_to>:now)
                ORDER BY rule_set_id,version
                """)
                .param("now", Timestamp.from(now))
                .query((row, ignored) -> new ActiveRule(
                        row.getObject("rule_set_id", UUID.class), row.getInt("version"),
                        row.getString("scope_definition"), row.getString("rule_definition"),
                        row.getString("definition_sha256")))
                .list();
        for (ActiveRule rule : rules) {
            try {
                transactions.executeWithoutResult(status -> processBatch(rule));
            } catch (RuntimeException exception) {
                LOG.error("Market rule assessment failed [ruleSetId={}, version={}]",
                        rule.id(), rule.version(), exception);
            }
        }
    }

    private void processBatch(ActiveRule active) {
        MarketRuleDefinition rule = MarketRuleDefinition.parse(readTree(active.definition()));
        rule.requireMatchingScope(readTree(active.scope()));
        List<MarketSnapshot> snapshots = jdbc.sql("""
                SELECT fact.snapshot_id,fact.source_record_id,fact.source_version,
                       fact.ingested_at,fact.payload_sha256,fact.payload::text,fact.source_status,
                       NOT EXISTS (
                           SELECT 1 FROM risk.source_fact_snapshot newer
                           WHERE newer.source_system=fact.source_system
                             AND newer.source_record_type=fact.source_record_type
                             AND newer.source_record_id=fact.source_record_id
                             AND (newer.ingested_at>fact.ingested_at
                               OR (newer.ingested_at=fact.ingested_at
                                   AND newer.snapshot_id>fact.snapshot_id))
                       ) AS latest_version
                FROM risk.source_fact_snapshot fact
                WHERE fact.source_system='QIQIHAR_ENTERPRISE'
                  AND fact.source_record_type='MARKET_RECORD'
                  AND NOT EXISTS (
                      SELECT 1 FROM risk.market_rule_assessment_evaluation done
                      WHERE done.rule_set_id=:ruleId AND done.rule_set_version=:ruleVersion
                        AND done.source_fact_snapshot_id=fact.snapshot_id)
                ORDER BY fact.ingested_at,fact.snapshot_id
                LIMIT :limit
                """)
                .param("ruleId", active.id()).param("ruleVersion", active.version())
                .param("limit", BATCH_SIZE)
                .query((row, ignored) -> new MarketSnapshot(
                        row.getObject("snapshot_id", UUID.class), row.getString("source_record_id"),
                        row.getString("source_version"), row.getTimestamp("ingested_at").toInstant(),
                        row.getString("payload_sha256"), row.getString("payload"),
                        row.getString("source_status"), row.getBoolean("latest_version"))).list();
        for (MarketSnapshot snapshot : snapshots) {
            UUID assessmentId = null;
            if ("CURRENT".equals(snapshot.status()) && snapshot.latestVersion()) {
                Map<String, Object> payload = readPayload(snapshot.payload());
                if (rule.matches(payload)) {
                    assessmentId = insertAssessment(active, rule, snapshot, payload);
                }
            }
            jdbc.sql("""
                    INSERT INTO risk.market_rule_assessment_evaluation(
                      rule_set_id,rule_set_version,source_fact_snapshot_id,matched,
                      assessment_id,evaluated_at)
                    VALUES(:ruleId,:ruleVersion,:snapshotId,:matched,:assessmentId,:evaluatedAt)
                    ON CONFLICT(rule_set_id,rule_set_version,source_fact_snapshot_id) DO NOTHING
                    """)
                    .param("ruleId", active.id()).param("ruleVersion", active.version())
                    .param("snapshotId", snapshot.id()).param("matched", assessmentId != null)
                    .param("assessmentId", assessmentId)
                    .param("evaluatedAt", Timestamp.from(clock.instant())).update();
        }
    }

    private UUID insertAssessment(ActiveRule active, MarketRuleDefinition rule,
            MarketSnapshot snapshot, Map<String, Object> payload) {
        UUID assessmentId = UUID.nameUUIDFromBytes((active.id() + ":" + active.version()
                + ":" + snapshot.id()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceFactSnapshotId", snapshot.id().toString());
        evidence.put("sourceRecordId", snapshot.recordId());
        evidence.put("sourceVersion", snapshot.version());
        evidence.put("sourcePayloadSha256", snapshot.sha256());
        evidence.put("ruleDefinitionSha256", active.sha256());
        evidence.put("regionCode", payload.get("regionCode"));
        evidence.put("productCode", payload.get("productCode"));
        evidence.put("field", rule.field());
        evidence.put("observedValue", payload.get(rule.field()));
        evidence.put("operator", rule.operator());
        evidence.put("threshold", rule.threshold().toPlainString());
        jdbc.sql("""
                INSERT INTO risk.risk_assessment(
                  assessment_id,domain_code,subject_type,subject_id,source_event_id,
                  rule_set_id,rule_set_version,evaluation_mode,risk_level,reason_codes,
                  evidence_snapshot,evaluated_at,evaluation_duration_ms)
                VALUES(:id,'MARKET','MARKET_RECORD',:subject,NULL,
                       :ruleId,:version,'RULE',:level,ARRAY[:reason]::varchar(80)[],
                       CAST(:evidence AS jsonb),:evaluatedAt,0)
                ON CONFLICT(assessment_id) DO NOTHING
                """)
                .param("id", assessmentId).param("subject", snapshot.recordId())
                .param("ruleId", active.id()).param("version", active.version())
                .param("level", rule.riskLevel()).param("reason", rule.reasonCode())
                .param("evidence", writeJson(evidence))
                .param("evaluatedAt", Timestamp.from(clock.instant())).update();
        return assessmentId;
    }

    private JsonNode readTree(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid stored rule JSON", exception); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored market fact", exception); }
    }

    private String writeJson(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Assessment evidence is not JSON", exception); }
    }

    private record ActiveRule(UUID id, int version, String scope, String definition, String sha256) { }
    private record MarketSnapshot(UUID id, String recordId, String version, Instant ingestedAt,
            String sha256, String payload, String status, boolean latestVersion) { }
}
