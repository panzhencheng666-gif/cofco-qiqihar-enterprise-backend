package com.cofco.qiqihar.riskintelligence.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class AiKnowledgeDiscoveryTest {
    @Test
    void searchCandidatesNeedAConfiguredBackendAndOnlyPublicHttpsLinks() {
        var discovery = new AiKnowledgeDiscovery(mock(JdbcClient.class), new ObjectMapper(),
                mock(TransactionTemplate.class), Clock.systemUTC(), "", "");
        assertThatThrownBy(() -> discovery.discover("玉米市场", "root"))
                .isInstanceOf(RiskApiException.class)
                .extracting(error -> ((RiskApiException) error).code())
                .isEqualTo("AI_SEARCH_NOT_CONFIGURED");
        assertThat(AiKnowledgeDiscovery.publicHttps("https://www.lswz.gov.cn/article"))
                .isTrue();
        for (String url : new String[] {"http://news.example/article",
                "https://localhost/private", "https://127.0.0.1/private",
                "https://user@example.com/article", "https://example.com/article#fragment"}) {
            assertThat(AiKnowledgeDiscovery.publicHttps(url)).isFalse();
        }
    }
}
