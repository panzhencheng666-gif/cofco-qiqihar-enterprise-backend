package com.cofco.qiqihar.riskintelligence.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiAssistantNodeServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T14:30:00Z");
    private final AiAssistantRepository repository = mock(AiAssistantRepository.class);
    private final AiKnowledgeDiscovery discovery = mock(AiKnowledgeDiscovery.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AiAssistantNodeService service = new AiAssistantNodeService(
            repository, discovery, json, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(6));

    @Test
    void claimsWithABoundedShortLease() {
        var claim = new AiAssistantRepository.NodeClaim(
                UUID.randomUUID(), "问题", NOW.plus(Duration.ofMinutes(6)), 1);
        when(repository.claim("mac-node", NOW, Duration.ofMinutes(6)))
                .thenReturn(Optional.of(claim));

        assertThat(service.claim("mac-node")).contains(claim);
    }

    @Test
    void acceptsOnlyGroundedLocalAssistantResponses() {
        UUID id = UUID.randomUUID();
        var response = json.readTree("""
                {"status":"ANSWERED","mode":"FOUNDATION_RAG",
                 "modelReference":"qiliang-foundation-rag-qwen3.8-27b",
                 "knowledgeVersion":"2026-09-22.v1","answer":"回答",
                 "citations":[{"id":"source-1","title":"来源","url":"https://example.test/source",
                  "use":"RETRIEVAL_ONLY"}],"limitations":["限制"]}
                """);
        when(repository.complete(id, "mac-node", response, NOW)).thenReturn(true);

        assertThat(service.complete(id, "mac-node", response)).isTrue();
        verify(repository).complete(id, "mac-node", response, NOW);

        assertThatThrownBy(() -> service.complete(id, "mac-node", json.readTree(
                "{\"status\":\"ANSWERED\",\"answer\":\"无引用回答\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("响应结构");

        assertThatThrownBy(() -> service.complete(id, "mac-node", json.readTree("""
                {"status":"ANSWERED","mode":"FOUNDATION_RAG","modelReference":"/private/model/path",
                 "knowledgeVersion":"v1","answer":"回答","citations":[{"id":"s1"}],
                 "limitations":[7]}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("响应结构");

        assertThatThrownBy(() -> service.complete(id, "mac-node", json.readTree("""
                {"status":"ANSWERED","mode":"FOUNDATION_RAG",
                 "modelReference":"qiliang-foundation-rag-qwen3.8-27b",
                 "knowledgeVersion":"v1","answer":"回答",
                 "citations":[{"id":"s1","title":"来源",
                  "url":"https://user@example.test/source","use":"RETRIEVAL_ONLY"}],
                 "limitations":["限制"]}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("响应结构");
    }

    @Test
    void acceptsExplicitlyUnverifiedGeneralAnswerWithoutCitation() {
        UUID id = UUID.randomUUID();
        var response = json.readTree("""
                {"status":"ANSWERED","mode":"FOUNDATION_GENERAL",
                 "modelReference":"qiliang-foundation-rag-qwen3.8-27b",
                 "knowledgeVersion":"model-knowledge-unverified-v1",
                 "answer":"通识回答","citations":[],"limitations":["来源未核验"]}
                """);
        when(repository.complete(id, "mac-node", response, NOW)).thenReturn(true);
        assertThat(service.complete(id, "mac-node", response)).isTrue();
    }

    @Test
    void acceptsOptionalSearchCitationProvenanceAndTime() {
        UUID id = UUID.randomUUID();
        var response = json.readTree("""
                {"status":"ANSWERED","mode":"FOUNDATION_RAG",
                 "modelReference":"qiliang-foundation-rag-qwen3.8-27b",
                 "knowledgeVersion":"v1","answer":"回答",
                 "citations":[{"id":"source-1","title":"来源",
                  "url":"https://example.test/source","use":"RETRIEVAL_ONLY",
                  "searchedAt":"2026-09-27T16:30:00Z",
                  "publishedAt":"2026-09-26T08:15:00Z",
                  "sourceType":"NEWS_HEADLINE"}],"limitations":["限制"]}
                """);
        when(repository.complete(id, "mac-node", response, NOW)).thenReturn(true);

        assertThat(service.complete(id, "mac-node", response)).isTrue();
        verify(repository).complete(id, "mac-node", response, NOW);
    }

    @Test
    void registersPublicSearchResultsOnlyAfterAcceptedCompletion() {
        UUID id = UUID.randomUUID();
        var response = json.readTree("""
                {"status":"ANSWERED","mode":"FOUNDATION_GENERAL",
                 "modelReference":"qiliang-foundation-rag-qwen3.8-27b",
                 "knowledgeVersion":"v1","answer":"通识回答","citations":[],
                 "limitations":["来源未核验"],
                 "webCandidates":[{"title":"公开报道","url":"https://example.gov.cn/news",
                   "snippet":"未经核验的公开搜索摘要"}]}
                """);
        var storedAnswer = response.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) storedAnswer).remove("webCandidates");
        when(repository.complete(id, "mac-node", storedAnswer, NOW)).thenReturn(true);

        assertThat(service.complete(id, "mac-node", response)).isTrue();
        verify(repository).complete(id, "mac-node", storedAnswer, NOW);
        verify(discovery).registerAssistantCandidates(List.of(
                new AiKnowledgeDiscovery.Candidate("公开报道", "https://example.gov.cn/news",
                        "未经核验的公开搜索摘要")), "mac-node");

        UUID stale = UUID.randomUUID();
        assertThat(service.complete(stale, "mac-node", response)).isFalse();
        verify(discovery).registerAssistantCandidates(List.of(
                new AiKnowledgeDiscovery.Candidate("公开报道", "https://example.gov.cn/news",
                        "未经核验的公开搜索摘要")), "mac-node");
    }

    @Test
    void rejectsPrivateOrMalformedSearchCandidatesBeforeWritingAnswer() {
        UUID id = UUID.randomUUID();
        for (String candidate : List.of(
                "{\"title\":\"x\",\"url\":\"https://127.0.0.1/private\",\"snippet\":\"text\"}",
                "{\"title\":\"x\",\"url\":\"https://example.gov.cn/\",\"snippet\":\"text\",\"approved\":true}")) {
            var response = json.readTree("""
                    {"status":"ANSWERED","mode":"FOUNDATION_GENERAL",
                     "modelReference":"qiliang-foundation-rag-qwen3.8-27b",
                     "knowledgeVersion":"v1","answer":"回答","citations":[],
                     "limitations":["来源未核验"],"webCandidates":[%s]}
                    """.formatted(candidate));
            assertThatThrownBy(() -> service.complete(id, "mac-node", response))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("响应结构");
        }
        verify(repository, never()).complete(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
