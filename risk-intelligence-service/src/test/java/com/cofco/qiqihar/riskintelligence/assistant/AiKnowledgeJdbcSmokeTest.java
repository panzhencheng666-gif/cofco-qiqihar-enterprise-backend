package com.cofco.qiqihar.riskintelligence.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Runs only against a disposable PostgreSQL database with V222 and V223 installed. */
@EnabledIfEnvironmentVariable(named = "RISK_KNOWLEDGE_SMOKE_URL", matches = ".*knowledge_smoke.*")
class AiKnowledgeJdbcSmokeTest {
    @Test
    void approvedSnapshotsAreVersionedScopedRetrievableAndWithdrawn() {
        var dataSource = new DriverManagerDataSource(System.getenv("RISK_KNOWLEDGE_SMOKE_URL"));
        var jdbc = JdbcClient.create(dataSource);
        var clock = Clock.fixed(Instant.parse("2026-09-24T08:00:00Z"), ZoneOffset.UTC);
        var registry = new AiKnowledgeController(jdbc, clock, mock(AiKnowledgeDiscovery.class));
        var retriever = new AiKnowledgeRetriever(jdbc);
        var root = request(true);
        var business = request(false);
        String key = "knowledge/integration-" + java.util.UUID.randomUUID();

        var draft = registry.register(new AiKnowledgeController.Registration(
                "玉米储藏正文", "https://example.org/official", key, "OFFICIAL",
                "BUSINESS", "UNKNOWN", "玉米储藏的真实正文快照。", "搜索摘要不是正文"),
                root).data();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(retriever.retrieve("玉米储藏", false)).noneMatch(
                source -> source.id().equals(draft.documentId().toString()));
        var published = registry.approve(draft.documentId(),
                new AiKnowledgeController.Approval("已核对原文和适用日期", false, null),
                root).data();
        assertThat(published.status()).isEqualTo("APPROVED");
        assertThat(retriever.retrieve("玉米储藏", false)).anyMatch(
                source -> source.id().equals(draft.documentId().toString()) &&
                        source.bodyExcerpt().contains("真实正文快照"));
        assertThat(registry.get(draft.documentId(), business).data().verificationNote())
                .isNull();

        var privateDraft = registry.register(new AiKnowledgeController.Registration(
                "内部玉米记录", "https://example.org/internal", key + "-private",
                "SYSTEM_RECORD", "ROOT", "OWNED", "内部玉米储藏记录。", null), root).data();
        registry.approve(privateDraft.documentId(),
                new AiKnowledgeController.Approval("已核对系统记录", false, null), root);
        assertThatThrownBy(() -> registry.get(privateDraft.documentId(), business))
                .isInstanceOf(RiskApiException.class);
        assertThat(retriever.retrieve("内部玉米储藏", false)).noneMatch(
                source -> source.id().equals(privateDraft.documentId().toString()));
        assertThat(retriever.retrieve("内部玉米储藏", true)).anyMatch(
                source -> source.id().equals(privateDraft.documentId().toString()));

        registry.retire(draft.documentId(), root);
        assertThat(retriever.retrieve("玉米储藏", false)).noneMatch(
                source -> source.id().equals(draft.documentId().toString()));
        assertThat(jdbc.sql("SELECT event_code FROM risk.ai_knowledge_audit WHERE document_id=:id")
                .param("id", draft.documentId()).query(String.class).list())
                .containsExactlyInAnyOrder("REGISTERED", "APPROVED", "RETIRED");
    }

    private static MockHttpServletRequest request(boolean root) {
        var request = new MockHttpServletRequest();
        request.setAttribute(RiskBusinessSession.REQUEST_ATTRIBUTE, new RiskBusinessSession(
                root ? "root" : "business", Set.of("BUSINESS_READ", "BUSINESS_UPDATE"),
                root, root ? Set.of() : Set.of("230200")));
        return request;
    }
}
