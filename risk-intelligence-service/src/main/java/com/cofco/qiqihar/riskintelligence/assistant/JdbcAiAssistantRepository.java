package com.cofco.qiqihar.riskintelligence.assistant;

import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcAiAssistantRepository implements AiAssistantRepository {
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AiKnowledgeRetriever knowledge;

    public JdbcAiAssistantRepository(JdbcClient jdbc, ObjectMapper json,
            AiKnowledgeRetriever knowledge) {
        this.jdbc = jdbc;
        this.json = json;
        this.knowledge = knowledge;
    }

    @Override
    public RequestView create(String subjectId, String idempotencyKey, String question,
            boolean rootAccess, Instant now) {
        jdbc.sql("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked")
                .param("key", subjectId.length() + ":" + subjectId + idempotencyKey)
                .query(Boolean.class).single();
        Optional<RequestIdentity> existing = jdbc.sql("""
                SELECT request_id,question FROM risk.ai_assistant_request
                WHERE requested_by_subject=:subject AND idempotency_key=:key FOR UPDATE
                """).param("subject", subjectId).param("key", idempotencyKey)
                .query((row, index) -> new RequestIdentity(
                        row.getObject("request_id", UUID.class), row.getString("question")))
                .optional();
        if (existing.isPresent()) {
            RequestIdentity identity = existing.orElseThrow();
            if (!question.equals(identity.question())) {
                throw new ConflictException("AI_ASSISTANT_IDEMPOTENCY_CONFLICT",
                        "同一幂等键已用于不同的AI问答请求");
            }
            return find(identity.requestId()).orElseThrow();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO risk.ai_assistant_request(
                  request_id,requested_by_subject,idempotency_key,question,root_access,
                  status,created_at,updated_at)
                VALUES(:id,:subject,:key,:question,:root,'QUEUED',:now,:now)
                """).param("id", id).param("subject", subjectId).param("question", question)
                .param("key", idempotencyKey).param("root", rootAccess)
                .param("now", timestamp(now)).update();
        audit(id, "BUSINESS", subjectId, "QUESTION_CREATED", now);
        return find(id).orElseThrow();
    }

    @Override
    public RequestView completeProductIdentity(UUID requestId, String subjectId, String answer, Instant now) {
        var response = json.createObjectNode();
        response.put("status", "ANSWERED");
        response.put("mode", "PRODUCT_IDENTITY");
        response.putNull("modelReference");
        response.put("knowledgeVersion", "qiliang-product-identity.v1");
        response.put("answer", answer);
        response.putArray("citations");
        response.putArray("limitations").add("产品身份说明由系统提供，未调用模型，不构成粮食业务结论。");
        int updated = jdbc.sql("""
                UPDATE risk.ai_assistant_request
                SET status='ANSWERED',mode='PRODUCT_IDENTITY',
                    knowledge_version='qiliang-product-identity.v1',model_reference=NULL,
                    answer=:answer,response_json=CAST(:response AS jsonb),
                    completed_at=:now,updated_at=:now
                WHERE request_id=:id AND requested_by_subject=:subject AND status='QUEUED'
                """).param("id", requestId).param("subject", subjectId)
                .param("answer", answer).param("response", write(response))
                .param("now", timestamp(now)).update();
        if (updated == 1) audit(requestId, "SYSTEM", "qiliang-product-identity",
                "PRODUCT_IDENTITY_ANSWERED", now);
        return find(requestId).orElseThrow();
    }

    @Override
    public Optional<RequestView> find(UUID requestId) {
        return jdbc.sql("""
                SELECT request_id,requested_by_subject,status,question,mode,knowledge_version,
                       model_reference,answer,response_json::text,failure_code,created_at,completed_at
                FROM risk.ai_assistant_request WHERE request_id=:id
                """).param("id", requestId).query(this::view).optional();
    }

    @Override
    public Optional<NodeClaim> claim(String nodeId, Instant now, Duration leaseDuration) {
        List<UUID> exhausted = jdbc.sql("""
                SELECT request_id FROM risk.ai_assistant_request
                WHERE status='RUNNING' AND lease_until<=:now AND attempt_count>=:maxAttempts
                FOR UPDATE
                """).param("now", timestamp(now)).param("maxAttempts", MAX_ATTEMPTS)
                .query(UUID.class).list();
        for (UUID requestId : exhausted) {
            int updated = jdbc.sql("""
                    UPDATE risk.ai_assistant_request
                    SET status='FAILED',lease_owner=NULL,lease_until=NULL,
                        failure_code='ASSISTANT_RETRY_EXHAUSTED',completed_at=:now,updated_at=:now
                    WHERE request_id=:id AND status='RUNNING' AND lease_until<=:now
                    """).param("id", requestId).param("now", timestamp(now)).update();
            if (updated == 1) audit(requestId, "SYSTEM", "assistant-retry-policy",
                    "RETRY_EXHAUSTED", now);
        }
        jdbc.sql("""
                UPDATE risk.ai_assistant_request
                SET status='QUEUED',lease_owner=NULL,lease_until=NULL,updated_at=:now
                WHERE status='RUNNING' AND lease_until<=:now AND attempt_count<:maxAttempts
                """).param("now", timestamp(now)).param("maxAttempts", MAX_ATTEMPTS).update();
        Optional<UUID> candidate = jdbc.sql("""
                SELECT request_id FROM risk.ai_assistant_request
                WHERE status='QUEUED' AND attempt_count<:maxAttempts ORDER BY created_at,request_id
                FOR UPDATE SKIP LOCKED LIMIT 1
                """).param("maxAttempts", MAX_ATTEMPTS).query(UUID.class).optional();
        if (candidate.isEmpty()) return Optional.empty();
        UUID requestId = candidate.orElseThrow();
        Instant leaseUntil = now.plus(leaseDuration);
        jdbc.sql("""
                UPDATE risk.ai_assistant_request
                SET status='RUNNING',lease_owner=:node,lease_until=:lease,
                    attempt_count=attempt_count+1,updated_at=:now
                WHERE request_id=:id
                """).param("node", nodeId).param("lease", timestamp(leaseUntil))
                .param("now", timestamp(now)).param("id", requestId).update();
        audit(requestId, "AI_NODE", nodeId, "CLAIMED", now);
        return jdbc.sql("""
                SELECT request_id,question,root_access,lease_until,attempt_count
                FROM risk.ai_assistant_request WHERE request_id=:id
                """).param("id", requestId).query((row, index) -> new NodeClaim(
                        row.getObject("request_id", UUID.class), row.getString("question"),
                        instant(row, "lease_until"), row.getInt("attempt_count"),
                        knowledge.retrieve(row.getString("question"), row.getBoolean("root_access"))
                )).optional();
    }

    @Override
    public boolean complete(UUID requestId, String nodeId, JsonNode response, Instant now) {
        Optional<Locked> locked = lock(requestId);
        if (locked.isEmpty()) return false;
        Locked state = locked.orElseThrow();
        if (state.terminal()) return response.equals(state.response());
        if (!state.ownedBy(nodeId, now)) return false;
        String status = response.path("status").asText();
        int updated = jdbc.sql("""
                UPDATE risk.ai_assistant_request
                SET status=:status,lease_owner=NULL,lease_until=NULL,
                    mode=:mode,knowledge_version=:knowledge,model_reference=:model,
                    answer=:answer,response_json=CAST(:response AS jsonb),failure_code=NULL,
                    completed_at=:now,updated_at=:now
                WHERE request_id=:id
                """).param("status", status).param("mode", response.path("mode").asText())
                .param("knowledge", response.path("knowledgeVersion").asText())
                .param("model", response.path("modelReference").asText())
                .param("answer", response.path("answer").asText())
                .param("response", write(response)).param("now", timestamp(now))
                .param("id", requestId).update();
        if (updated == 1) audit(requestId, "AI_NODE", nodeId, status, now);
        return updated == 1;
    }

    @Override
    public boolean fail(UUID requestId, String nodeId, String failureCode, Instant now) {
        Optional<Locked> locked = lock(requestId);
        if (locked.isEmpty()) return false;
        Locked state = locked.orElseThrow();
        if (state.terminal()) return "FAILED".equals(state.status())
                && failureCode.equals(state.failureCode());
        if (!state.ownedBy(nodeId, now)) return false;
        int updated = jdbc.sql("""
                UPDATE risk.ai_assistant_request
                SET status='FAILED',lease_owner=NULL,lease_until=NULL,failure_code=:failure,
                    completed_at=:now,updated_at=:now WHERE request_id=:id
                """).param("failure", failureCode).param("now", timestamp(now))
                .param("id", requestId).update();
        if (updated == 1) audit(requestId, "AI_NODE", nodeId, "FAILED", now);
        return updated == 1;
    }

    private Optional<Locked> lock(UUID requestId) {
        return jdbc.sql("""
                SELECT status,lease_owner,lease_until,response_json::text,failure_code
                FROM risk.ai_assistant_request WHERE request_id=:id FOR UPDATE
                """).param("id", requestId).query((row, index) -> new Locked(
                        row.getString("status"), row.getString("lease_owner"),
                        instantNullable(row, "lease_until"), readNullable(row.getString("response_json")),
                        row.getString("failure_code"))).optional();
    }

    private RequestView view(ResultSet row, int index) throws SQLException {
        JsonNode response = readNullable(row.getString("response_json"));
        return new RequestView(
                row.getObject("request_id", UUID.class), row.getString("requested_by_subject"),
                row.getString("status"), row.getString("question"), row.getString("mode"),
                row.getString("knowledge_version"), row.getString("model_reference"),
                row.getString("answer"), response == null ? null : response.path("citations").deepCopy(),
                response == null ? null : response.path("limitations").deepCopy(),
                row.getString("failure_code"), instant(row, "created_at"),
                instantNullable(row, "completed_at"));
    }

    private void audit(UUID requestId, String actorType, String actorId, String event, Instant now) {
        jdbc.sql("""
                INSERT INTO risk.ai_assistant_audit(
                  event_id,request_id,actor_type,actor_id,event_code,occurred_at)
                VALUES(:eventId,:requestId,:actorType,:actorId,:event,:now)
                """).param("eventId", UUID.randomUUID()).param("requestId", requestId)
                .param("actorType", actorType).param("actorId", actorId).param("event", event)
                .param("now", timestamp(now)).update();
    }

    private String write(JsonNode value) {
        return json.writeValueAsString(value);
    }

    private JsonNode readNullable(String value) {
        return value == null ? null : json.readTree(value);
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Instant instantNullable(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Locked(String status, String leaseOwner, Instant leaseUntil,
            JsonNode response, String failureCode) {
        boolean terminal() {
            return "ANSWERED".equals(status) || "INSUFFICIENT_EVIDENCE".equals(status)
                    || "FAILED".equals(status);
        }

        boolean ownedBy(String nodeId, Instant now) {
            return "RUNNING".equals(status) && nodeId.equals(leaseOwner)
                    && leaseUntil != null && leaseUntil.isAfter(now);
        }
    }

    private record RequestIdentity(UUID requestId, String question) { }
}
