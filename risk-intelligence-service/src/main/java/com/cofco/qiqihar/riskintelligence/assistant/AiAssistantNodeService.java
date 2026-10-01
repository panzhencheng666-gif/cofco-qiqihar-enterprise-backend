package com.cofco.qiqihar.riskintelligence.assistant;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AiAssistantNodeService {
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "status", "mode", "modelReference", "knowledgeVersion",
            "answer", "citations", "limitations");
    private static final Set<String> REQUIRED_CITATION_FIELDS = Set.of("id", "title", "url", "use");
    private static final Set<String> OPTIONAL_CITATION_FIELDS = Set.of(
            "searchedAt", "publishedAt", "sourceType");
    private static final Set<String> CANDIDATE_FIELDS = Set.of("title", "url", "snippet");
    private static final String MODEL_REFERENCE = "qiliang-foundation-rag-qwen3.8-27b";
    private final AiAssistantRepository repository;
    private final AiKnowledgeDiscovery discovery;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration leaseDuration;

    public AiAssistantNodeService(AiAssistantRepository repository, AiKnowledgeDiscovery discovery,
            ObjectMapper json, Clock clock,
            @Value("${qiqihar.risk.assistant.lease-duration:6m}") Duration leaseDuration) {
        this.repository = repository;
        this.discovery = discovery;
        this.json = json;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    @Transactional
    public Optional<AiAssistantRepository.NodeClaim> claim(String nodeId) {
        return repository.claim(nodeId, clock.instant(), leaseDuration);
    }

    @Transactional
    public boolean complete(UUID requestId, String nodeId, JsonNode response) {
        validate(response);
        List<AiKnowledgeDiscovery.Candidate> candidates = candidates(response.path("webCandidates"));
        ObjectNode answer = (ObjectNode) response.deepCopy();
        answer.remove("webCandidates");
        if (!repository.complete(requestId, nodeId, answer, clock.instant())) return false;
        discovery.registerAssistantCandidates(candidates, nodeId);
        return true;
    }

    @Transactional
    public boolean fail(UUID requestId, String nodeId, String failureCode) {
        if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException("失败代码不合法");
        }
        return repository.fail(requestId, nodeId, failureCode, clock.instant());
    }

    private void validate(JsonNode response) {
        if (response == null || !response.isObject()
                || response.toString().getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("AI响应结构不合法");
        }
        Set<String> fields = new HashSet<>();
        response.properties().forEach(entry -> fields.add(entry.getKey()));
        String status = response.path("status").asText("");
        boolean answered = "ANSWERED".equals(status);
        if (!(fields.equals(RESPONSE_FIELDS) ||
                fields.size() == RESPONSE_FIELDS.size() + 1 &&
                fields.containsAll(RESPONSE_FIELDS) && fields.contains("webCandidates"))
                || !(answered || "INSUFFICIENT_EVIDENCE".equals(status))
                || !("FOUNDATION_RAG".equals(response.path("mode").asText())
                     || "FOUNDATION_GENERAL".equals(response.path("mode").asText()))
                || !MODEL_REFERENCE.equals(response.path("modelReference").asText())
                || !boundedText(response.path("knowledgeVersion"), 160)
                || !boundedText(response.path("answer"), 6000)
                || !response.path("citations").isArray()
                || !response.path("limitations").isArray()
                || (answered && "FOUNDATION_RAG".equals(response.path("mode").asText())
                    && response.path("citations").isEmpty())
                || ("FOUNDATION_GENERAL".equals(response.path("mode").asText())
                    && !response.path("citations").isEmpty())
                || !validCitations(response.path("citations"))
                || !validLimitations(response.path("limitations"))
                || !validCandidates(response.path("webCandidates"))) {
            throw new IllegalArgumentException("AI响应结构不合法");
        }
        try {
            json.writeValueAsString(response);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("AI响应结构不合法", exception);
        }
    }

    private static boolean validCandidates(JsonNode candidates) {
        if (candidates.isMissingNode()) return true;
        if (!candidates.isArray() || candidates.size() > 2) return false;
        for (JsonNode candidate : candidates) {
            if (!candidate.isObject()) return false;
            Set<String> fields = new HashSet<>();
            candidate.properties().forEach(entry -> fields.add(entry.getKey()));
            if (!fields.equals(CANDIDATE_FIELDS)
                    || !boundedText(candidate.path("title"), 300)
                    || !boundedText(candidate.path("url"), 800)
                    || !AiKnowledgeDiscovery.publicHttps(candidate.path("url").asText())
                    || !boundedText(candidate.path("snippet"), 4000)) return false;
        }
        return true;
    }

    private static List<AiKnowledgeDiscovery.Candidate> candidates(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<AiKnowledgeDiscovery.Candidate> candidates = new ArrayList<>();
        for (JsonNode candidate : value) {
            candidates.add(new AiKnowledgeDiscovery.Candidate(
                    candidate.path("title").asText(), candidate.path("url").asText(),
                    candidate.path("snippet").asText()));
        }
        return candidates;
    }

    private static boolean validCitations(JsonNode citations) {
        if (citations.size() > 20) return false;
        for (JsonNode citation : citations) {
            Set<String> fields = new HashSet<>();
            if (!citation.isObject()) return false;
            citation.properties().forEach(entry -> fields.add(entry.getKey()));
            if (!fields.containsAll(REQUIRED_CITATION_FIELDS)
                    || fields.stream().anyMatch(field -> !REQUIRED_CITATION_FIELDS.contains(field)
                            && !OPTIONAL_CITATION_FIELDS.contains(field))
                    || !boundedText(citation.path("id"), 160)
                    || !boundedText(citation.path("title"), 300)
                    || !boundedText(citation.path("url"), 2000)
                    || !validHttpsUrl(citation.path("url").asText())
                    || (fields.contains("searchedAt") && !validCitationTime(citation.path("searchedAt")))
                    || (fields.contains("publishedAt") && !validCitationTime(citation.path("publishedAt")))
                    || (fields.contains("sourceType") && !Set.of(
                            "SEARCH_SNIPPET", "PUBLIC_PAGE_EXCERPT", "NEWS_HEADLINE")
                            .contains(citation.path("sourceType").asText()))
                    || !"RETRIEVAL_ONLY".equals(citation.path("use").asText())) return false;
        }
        return true;
    }

    private static boolean validLimitations(JsonNode limitations) {
        if (limitations.size() > 20) return false;
        for (JsonNode limitation : limitations) {
            if (!boundedText(limitation, 2000)) return false;
        }
        return true;
    }

    private static boolean boundedText(JsonNode value, int maxLength) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= maxLength;
    }

    private static boolean validHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validCitationTime(JsonNode value) {
        if (!boundedText(value, 40)) return false;
        try {
            Instant.parse(value.asText());
            return true;
        } catch (RuntimeException ignored) {
            try {
                LocalDate.parse(value.asText());
                return true;
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }
}
