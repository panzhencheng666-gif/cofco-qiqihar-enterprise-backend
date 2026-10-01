package com.cofco.qiqihar.riskintelligence.assistant;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Root-governed, immutable text snapshots. Search excerpts stay in DRAFT until the body is checked. */
@RestController
@RequestMapping("/api/v1/risk/assistant/knowledge")
public class AiKnowledgeController {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AiKnowledgeDiscovery discovery;

    public AiKnowledgeController(JdbcClient jdbc, Clock clock, AiKnowledgeDiscovery discovery) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.discovery = discovery;
    }

    @PostMapping("/discover")
    ApiResponse<AiKnowledgeDiscovery.DiscoveryResult> discover(
            @RequestBody DiscoveryQuery input, HttpServletRequest request) {
        if (input == null) bad("搜索请求不合法");
        return new ApiResponse<>(discovery.discover(input.query(), root(request).subjectId()));
    }

    @PostMapping
    @Transactional
    ApiResponse<Document> register(@RequestBody Registration input, HttpServletRequest request) {
        String actor = root(request).subjectId();
        if (input == null) bad("资料请求不合法");
        requireText(input.title(), 300, "资料标题");
        requireUrl(input.sourceUrl());
        requireText(input.sourceKind(), 24, "来源类别");
        if (!List.of("SYSTEM_RECORD", "OFFICIAL", "MEDIA", "OTHER", "SEARCH_CANDIDATE")
                .contains(input.sourceKind())) {
            bad("来源类别不合法");
        }
        requireText(input.licenseCode(), 40, "授权状态");
        if (!List.of("UNKNOWN", "CC0-1.0", "CC-BY-4.0", "PUBLIC_DOMAIN", "OWNED")
                .contains(input.licenseCode())) bad("授权状态不合法");
        if (!List.of("ROOT", "BUSINESS").contains(input.accessScope())) bad("访问范围不合法");
        if ("SYSTEM_RECORD".equals(input.sourceKind()) && !"ROOT".equals(input.accessScope())) {
            bad("系统记录在区域权限接入前仅限管理员检索");
        }
        if (!"SYSTEM_RECORD".equals(input.sourceKind()) &&
                !AiKnowledgeDiscovery.publicHttps(input.sourceUrl())) {
            bad("公开来源地址须为公网 HTTPS 地址");
        }
        String body = blankToNull(input.bodyText());
        String excerpt = blankToNull(input.searchExcerpt());
        if (body == null && excerpt == null) bad("须提供正文快照或带标记的搜索摘要");
        if (body != null && body.length() > 1_000_000) bad("正文快照过长");
        if (excerpt != null && excerpt.length() > 4_000) bad("搜索摘要过长");
        String objectKey = input.objectKey();
        if (objectKey == null || !objectKey.matches("knowledge/[A-Za-z0-9._/-]{1,900}")) {
            bad("资料逻辑键不合法");
        }
        // Serialize version allocation for this logical source. A correction always gets a new row.
        jdbc.sql("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked")
                .param("key", objectKey).query(Boolean.class).single();
        Integer prior = jdbc.sql("SELECT max(version) FROM risk.ai_knowledge_document WHERE object_key=:key")
                .param("key", objectKey).query(Integer.class).optional().orElse(null);
        int version = prior == null ? 1 : prior + 1;
        UUID id = UUID.randomUUID();
        String hash = sha256(body == null ? excerpt : body);
        jdbc.sql("""
                INSERT INTO risk.ai_knowledge_document(
                  document_id,title,source_url,object_key,content_sha256,version,status,
                  use_scope,access_scope,license_code,source_kind,body_text,search_excerpt,
                  created_by_subject,created_at)
                VALUES(:id,:title,:url,:key,:hash,:version,'DRAFT',
                  'RETRIEVAL_ONLY',:scope,:license,:kind,:body,:excerpt,:actor,:now)
                """).param("id", id).param("title", input.title().strip())
                .param("url", input.sourceUrl()).param("key", objectKey)
                .param("hash", hash).param("version", version)
                .param("scope", input.accessScope()).param("license", input.licenseCode())
                .param("kind", input.sourceKind()).param("body", body).param("excerpt", excerpt)
                .param("actor", actor).param("now", java.sql.Timestamp.from(clock.instant())).update();
        audit(id, actor, "REGISTERED");
        return new ApiResponse<>(find(id));
    }

    @PostMapping("/{id}/approve")
    @Transactional
    ApiResponse<Document> approve(@PathVariable UUID id, @RequestBody Approval input,
            HttpServletRequest request) {
        String actor = root(request).subjectId();
        if (input == null) bad("核验请求不合法");
        requireText(input.verificationNote(), 4000, "正文核验记录");
        if (input.trainingAllowed()) {
            requireUrl(input.rightsEvidence());
        }
        Document document = lock(id);
        if (!"DRAFT".equals(document.status()) || document.bodyText() == null ||
                "SEARCH_CANDIDATE".equals(document.sourceKind())) {
            throw new RiskApiException(HttpStatus.CONFLICT, "AI_KNOWLEDGE_NOT_APPROVABLE",
                    "仅有正文快照的草稿可经核验发布");
        }
        if (input.trainingAllowed() && "UNKNOWN".equals(document.licenseCode())) {
            bad("训练使用权尚未确认");
        }
        jdbc.sql("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked")
                .param("key", document.objectKey()).query(Boolean.class).single();
        int latest = jdbc.sql("""
                SELECT max(version) FROM risk.ai_knowledge_document WHERE object_key=:key
                """).param("key", document.objectKey()).query(Integer.class).single();
        if (document.version() != latest) {
            throw new RiskApiException(HttpStatus.CONFLICT, "AI_KNOWLEDGE_VERSION_SUPERSEDED",
                    "只能发布资料的最新版本");
        }
        List<UUID> superseded = jdbc.sql("""
                SELECT document_id FROM risk.ai_knowledge_document
                WHERE object_key=:key AND status='APPROVED' FOR UPDATE
                """).param("key", document.objectKey()).query(UUID.class).list();
        for (UUID oldId : superseded) {
            jdbc.sql("""
                    UPDATE risk.ai_knowledge_document
                    SET status='RETIRED',approved_by_subject=NULL,approved_at=NULL,
                        use_scope='RETRIEVAL_ONLY',retired_by_subject=:actor,retired_at=:now
                    WHERE document_id=:id
                    """).param("id", oldId).param("actor", actor)
                    .param("now", java.sql.Timestamp.from(clock.instant())).update();
            audit(oldId, actor, "RETIRED");
        }
        jdbc.sql("""
                UPDATE risk.ai_knowledge_document
                SET status='APPROVED',use_scope=:use,verification_note=:note,
                    rights_evidence=:rights,approved_by_subject=:actor,approved_at=:now
                WHERE document_id=:id
                """).param("id", id).param("use", input.trainingAllowed()
                        ? "TRAINING_ALLOWED" : "RETRIEVAL_ONLY")
                .param("note", input.verificationNote().strip())
                .param("rights", input.trainingAllowed() ? input.rightsEvidence().strip() : null)
                .param("actor", actor).param("now", java.sql.Timestamp.from(clock.instant())).update();
        audit(id, actor, "APPROVED");
        return new ApiResponse<>(find(id));
    }

    @PostMapping("/{id}/retire")
    @Transactional
    ApiResponse<Document> retire(@PathVariable UUID id, HttpServletRequest request) {
        String actor = root(request).subjectId();
        Document document = lock(id);
        if ("RETIRED".equals(document.status())) return new ApiResponse<>(document);
        jdbc.sql("""
                UPDATE risk.ai_knowledge_document
                SET status='RETIRED',approved_by_subject=NULL,approved_at=NULL,
                    use_scope='RETRIEVAL_ONLY',retired_by_subject=:actor,retired_at=:now
                WHERE document_id=:id
                """).param("id", id).param("actor", actor)
                .param("now", java.sql.Timestamp.from(clock.instant())).update();
        audit(id, actor, "RETIRED");
        return new ApiResponse<>(find(id));
    }

    @GetMapping
    ApiResponse<List<Document>> list(HttpServletRequest request) {
        root(request);
        return new ApiResponse<>(jdbc.sql("""
                SELECT document_id,title,source_url,object_key,content_sha256,version,status,
                       use_scope,access_scope,license_code,source_kind,
                       NULL::text AS body_text,search_excerpt,
                       verification_note,rights_evidence,created_by_subject,created_at,
                       approved_by_subject,approved_at,retired_by_subject,retired_at
                FROM risk.ai_knowledge_document ORDER BY created_at DESC,document_id DESC LIMIT 200
                """).query((row, index) -> map(row)).list());
    }

    @GetMapping("/{id}")
    ApiResponse<Document> get(@PathVariable UUID id, HttpServletRequest request) {
        RiskBusinessSession session = RiskBusinessSession.require(request);
        Document document = find(id);
        if (!session.rootAdministrator() &&
                !("APPROVED".equals(document.status()) &&
                  "BUSINESS".equals(document.accessScope()))) {
            throw new RiskApiException(HttpStatus.FORBIDDEN, "AI_KNOWLEDGE_FORBIDDEN",
                    "无权查看该资料快照");
        }
        return new ApiResponse<>(session.rootAdministrator() ? document : document.publicView());
    }

    private Document lock(UUID id) {
        return query(id, true);
    }

    private Document find(UUID id) {
        return query(id, false);
    }

    private Document query(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT document_id,title,source_url,object_key,content_sha256,version,status,
                       use_scope,access_scope,license_code,source_kind,body_text,search_excerpt,
                       verification_note,rights_evidence,created_by_subject,created_at,
                       approved_by_subject,approved_at,retired_by_subject,retired_at
                FROM risk.ai_knowledge_document WHERE document_id=:id
                """ + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((row, index) -> map(row)).optional()
                .orElseThrow(() -> new RiskApiException(HttpStatus.NOT_FOUND,
                        "AI_KNOWLEDGE_NOT_FOUND", "未找到资料快照"));
    }

    private static Document map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Document(row.getObject("document_id", UUID.class),row.getString("title"),
                row.getString("source_url"),row.getString("object_key"),
                row.getString("content_sha256"),row.getInt("version"),row.getString("status"),
                row.getString("use_scope"),row.getString("access_scope"),
                row.getString("license_code"),row.getString("source_kind"),
                row.getString("body_text"),row.getString("search_excerpt"),
                row.getString("verification_note"),row.getString("rights_evidence"),
                row.getString("created_by_subject"),instant(row,"created_at"),
                row.getString("approved_by_subject"),instant(row,"approved_at"),
                row.getString("retired_by_subject"),instant(row,"retired_at"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static RiskBusinessSession root(HttpServletRequest request) {
        RiskBusinessSession session = RiskBusinessSession.require(request);
        if (!session.rootAdministrator()) {
            throw new RiskApiException(HttpStatus.FORBIDDEN, "AI_KNOWLEDGE_ROOT_REQUIRED",
                    "仅系统管理员可治理知识资料");
        }
        return session;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void requireText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) bad(label + "不合法");
    }

    private static void requireUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || uri.getFragment() != null || value.length() > 800) {
                bad("来源地址须为无凭据的 HTTPS 地址");
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            bad("来源地址不合法");
        }
    }

    private static void bad(String message) {
        throw new RiskApiException(HttpStatus.BAD_REQUEST, "AI_KNOWLEDGE_INVALID", message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void audit(UUID id, String actor, String event) {
        jdbc.sql("""
                INSERT INTO risk.ai_knowledge_audit(event_id,document_id,actor_subject,event_code,occurred_at)
                VALUES(:eventId,:id,:actor,:event,:now)
                """).param("eventId", UUID.randomUUID()).param("id", id)
                .param("actor", actor).param("event", event)
                .param("now", java.sql.Timestamp.from(clock.instant())).update();
    }

    record Registration(String title, String sourceUrl, String objectKey, String sourceKind,
            String accessScope, String licenseCode, String bodyText, String searchExcerpt) { }
    record DiscoveryQuery(String query) { }
    record Approval(String verificationNote, boolean trainingAllowed, String rightsEvidence) { }
    record Document(UUID documentId, String title, String sourceUrl, String objectKey,
            String contentSha256, int version, String status, String useScope,
            String accessScope, String licenseCode, String sourceKind, String bodyText,
            String searchExcerpt, String verificationNote, String rightsEvidence,
            String createdBySubject, Instant createdAt, String approvedBySubject,
            Instant approvedAt, String retiredBySubject, Instant retiredAt) {
        Document publicView() {
            return new Document(documentId, title, sourceUrl, objectKey, contentSha256,
                    version, status, useScope, accessScope, licenseCode, sourceKind,
                    bodyText, null, null, null, null, createdAt, null,
                    approvedAt, null, retiredAt);
        }
    }
}
