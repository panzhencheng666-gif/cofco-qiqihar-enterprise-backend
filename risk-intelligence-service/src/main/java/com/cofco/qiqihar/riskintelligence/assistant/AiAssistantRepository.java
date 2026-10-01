package com.cofco.qiqihar.riskintelligence.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import tools.jackson.databind.JsonNode;

public interface AiAssistantRepository {
    RequestView create(String subjectId, String idempotencyKey, String question, boolean rootAccess,
            Instant now);
    default RequestView create(String subjectId, String idempotencyKey, String question, Instant now) {
        return create(subjectId, idempotencyKey, question, false, now);
    }
    RequestView completeProductIdentity(UUID requestId, String subjectId, String answer, Instant now);
    Optional<RequestView> find(UUID requestId);
    Optional<NodeClaim> claim(String nodeId, Instant now, Duration leaseDuration);
    boolean complete(UUID requestId, String nodeId, JsonNode response, Instant now);
    boolean fail(UUID requestId, String nodeId, String failureCode, Instant now);

    record RequestView(UUID requestId, String subjectId, String status, String question,
            String mode, String knowledgeVersion, String modelReference, String answer,
            JsonNode citations, JsonNode limitations, String failureCode,
            Instant createdAt, Instant completedAt) { }

    record KnowledgeSource(String id, String title, String url, String verifiedDate,
            String bodyExcerpt, String contentSha256, String sourceKind, int version,
            String use) { }

    record NodeClaim(UUID requestId, String question, Instant leaseUntil, int attempt,
            List<KnowledgeSource> sources) {
        public NodeClaim(UUID requestId, String question, Instant leaseUntil, int attempt) {
            this(requestId, question, leaseUntil, attempt, List.of());
        }
    }
}
