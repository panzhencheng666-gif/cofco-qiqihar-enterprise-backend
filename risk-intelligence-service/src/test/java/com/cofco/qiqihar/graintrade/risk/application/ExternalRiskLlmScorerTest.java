package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExternalRiskLlmScorerTest {
    @Test
    void returnsOnlyBoundedProbabilityFromConfiguredScorer() throws Exception {
        HttpClient http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"predictedPositive\":true,\"positiveProbability\":0.87}".getBytes());
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var scorer=new ExternalRiskLlmScorer(new ObjectMapper(),http,
                "http://127.0.0.1:63200/v1/score","secret");

        assertThat(scorer.score(task()).positiveProbability()).isEqualTo(0.87d);
        assertThat(scorer.score(task()).predictedPositive()).isTrue();
    }

    private static RiskScoringTask task() {
        return new RiskScoringTask(UUID.randomUUID(),1,"DOMAIN_LLM",
                "mlx-community/Qwen3.8-27B-4bit","/tmp/adapter.tar.gz","a".repeat(64),
                UUID.randomUUID(),"{\"riskLevel\":\"HIGH\"}","SHADOW");
    }
}
