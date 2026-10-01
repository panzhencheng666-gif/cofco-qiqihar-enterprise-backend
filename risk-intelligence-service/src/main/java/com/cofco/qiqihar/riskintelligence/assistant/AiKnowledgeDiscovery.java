package com.cofco.qiqihar.riskintelligence.assistant;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Search discovers unverified candidates; it never publishes search snippets as evidence. */
@Service
public class AiKnowledgeDiscovery {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String braveKey;
    private final String searxUrl;
    private final HttpClient http;

    public AiKnowledgeDiscovery(JdbcClient jdbc, ObjectMapper json,
            TransactionTemplate transactions, Clock clock,
            @Value("${qiqihar.risk.assistant.search.brave-key:${BRAVE_SEARCH_API_KEY:}}") String braveKey,
            @Value("${qiqihar.risk.assistant.search.searx-url:${SEARXNG_URL:}}") String searxUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
        this.clock = clock;
        this.braveKey = braveKey;
        this.searxUrl = searxUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public DiscoveryResult discover(String query, String actor) {
        if (query == null || query.isBlank() || query.length() > 160) {
            throw new RiskApiException(HttpStatus.BAD_REQUEST, "AI_SEARCH_QUERY_INVALID",
                    "搜索词须为1至160字");
        }
        if (braveKey.isBlank() && searxUrl.isBlank()) {
            throw new RiskApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SEARCH_NOT_CONFIGURED",
                    "后台搜索服务尚未配置");
        }
        URI endpoint = endpoint(query.strip());
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (searxUrl.isBlank()) builder.header("X-Subscription-Token", braveKey);
        JsonNode result;
        try {
            HttpResponse<java.io.InputStream> response = http.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(2_000_001);
                if (response.statusCode() != 200 || bytes.length > 2_000_000) {
                    throw new RiskApiException(HttpStatus.BAD_GATEWAY, "AI_SEARCH_FAILED",
                            "后台搜索服务响应异常");
                }
                result = json.readTree(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RiskApiException(HttpStatus.BAD_GATEWAY, "AI_SEARCH_FAILED", "后台搜索中断");
        } catch (IOException | IllegalArgumentException exception) {
            throw new RiskApiException(HttpStatus.BAD_GATEWAY, "AI_SEARCH_FAILED", "后台搜索失败");
        }
        JsonNode matches = searxUrl.isBlank() ? result.path("web").path("results")
                : result.path("results");
        if (!matches.isArray()) {
            throw new RiskApiException(HttpStatus.BAD_GATEWAY, "AI_SEARCH_FAILED",
                    "后台搜索结果格式异常");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode match : matches) {
            if (candidates.size() >= 10) break;
            String title = match.path("title").asText("").strip();
            String url = match.path("url").asText("").strip();
            String excerpt = match.path(searxUrl.isBlank() ? "description" : "content")
                    .asText("").strip();
            if (title.isEmpty() || excerpt.isEmpty() || !publicHttps(url)) continue;
            candidates.add(new Candidate(title.substring(0, Math.min(300, title.length())),
                    url, excerpt.substring(0, Math.min(4000, excerpt.length()))));
        }
        int registered = transactions.execute(status -> save(candidates, actor));
        return new DiscoveryResult(matches.size(), candidates.size(), registered,
                "搜索标题与摘要仅为候选；未读取正文、未核验事实、未确认训练使用权");
    }

    private int save(List<Candidate> candidates, String actor) {
        int inserted = 0;
        for (Candidate candidate : candidates) {
            String key = "knowledge/search-" + sha256(candidate.url()).substring(0, 40);
            UUID id = UUID.randomUUID();
            int changed = jdbc.sql("""
                    INSERT INTO risk.ai_knowledge_document(
                      document_id,title,source_url,object_key,content_sha256,version,status,
                      use_scope,access_scope,license_code,source_kind,search_excerpt,
                      created_by_subject,created_at)
                    VALUES(:id,:title,:url,:key,:hash,1,'DRAFT','RETRIEVAL_ONLY',
                      'ROOT','UNKNOWN','SEARCH_CANDIDATE',:excerpt,:actor,:now)
                    ON CONFLICT (object_key,version) DO NOTHING
                    """).param("id", id).param("title", candidate.title())
                    .param("url", candidate.url()).param("key", key)
                    .param("hash", sha256(candidate.excerpt()))
                    .param("excerpt", candidate.excerpt()).param("actor", actor)
                    .param("now", java.sql.Timestamp.from(clock.instant())).update();
            if (changed == 1) {
                jdbc.sql("""
                        INSERT INTO risk.ai_knowledge_audit(
                          event_id,document_id,actor_subject,event_code,occurred_at)
                        VALUES(:eventId,:id,:actor,'REGISTERED',:now)
                        """).param("eventId", UUID.randomUUID()).param("id", id)
                        .param("actor", actor)
                        .param("now", java.sql.Timestamp.from(clock.instant())).update();
                inserted++;
            }
        }
        return inserted;
    }

    int registerAssistantCandidates(List<Candidate> candidates, String nodeId) {
        if (candidates.isEmpty()) return 0;
        return save(candidates, "TRAINING_NODE:" + nodeId);
    }

    private URI endpoint(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        if (searxUrl.isBlank()) {
            return URI.create("https://api.search.brave.com/res/v1/web/search?q=" + encoded +
                    "&count=10");
        }
        URI base;
        try {
            base = URI.create(searxUrl);
        } catch (IllegalArgumentException exception) {
            throw new RiskApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_SEARCH_NOT_CONFIGURED", "后台搜索地址不合法");
        }
        if (!"https".equals(base.getScheme()) || base.getHost() == null ||
                base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new RiskApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_SEARCH_NOT_CONFIGURED", "后台搜索地址须为 HTTPS");
        }
        return URI.create(searxUrl.replaceAll("/$", "") + "/search?q=" + encoded + "&format=json");
    }

    static boolean publicHttps(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || uri.getFragment() != null ||
                    uri.getPort() != -1 && uri.getPort() != 443 || url.length() > 800) return false;
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return !host.equals("localhost") && !host.endsWith(".local") &&
                    !host.contains(":") && !host.matches("[0-9.]+");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record Candidate(String title, String url, String excerpt) { }
    public record DiscoveryResult(int searchResults, int usableCandidates, int registered,
            String limitation) { }
}
