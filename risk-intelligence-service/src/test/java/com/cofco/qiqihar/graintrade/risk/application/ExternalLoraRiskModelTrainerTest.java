package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExternalLoraRiskModelTrainerTest {
    @Test
    void acceptsOnlyVerifiableArtifactsFromAConfiguredTrainer() throws Exception {
        HttpClient http=mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(("""
                {"artifactReference":"s3://risk-models/adapter-v1",
                 "artifactSha256":"%s","metrics":{"f1":0.91},
                 "thresholds":{"minimumF1":0.85}}
                """).formatted("a".repeat(64)).getBytes());
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var trainer=new ExternalLoraRiskModelTrainer(new ObjectMapper(),http,
                "https://trainer.internal.example/v1/train","secret");

        var artifact=trainer.train(job());

        assertThat(artifact.artifactReference()).isEqualTo("s3://risk-models/adapter-v1");
        assertThat(artifact.artifactSha256()).isEqualTo("a".repeat(64));
        assertThat(artifact.trainingKind()).isEqualTo("LORA_ADAPTER");
        assertThat(artifact.metrics()).containsEntry("f1",0.91d);
    }

    @Test
    void rejectsMalformedOrCredentialBearingEndpointsBeforeTraining() {
        assertThat(new ExternalLoraRiskModelTrainer(new ObjectMapper(),mock(HttpClient.class),
                "not-a-url","").supports("DOMAIN_LLM")).isFalse();
        assertThat(new ExternalLoraRiskModelTrainer(new ObjectMapper(),mock(HttpClient.class),
                "https://user:password@trainer.example/train","").supports("DOMAIN_LLM")).isFalse();
    }

    private static RiskTrainingJob job() {
        return new RiskTrainingJob(UUID.randomUUID(),"risk-reasoning-llm-v1","DOMAIN_LLM",
                "CROSS_DOMAIN","model-registry://risk-base",1,UUID.randomUUID(),17L,
                List.of(new RiskTrainingExample(UUID.randomUUID(),Instant.parse("2026-09-21T00:00:00Z"),
                        "{\"riskLevel\":\"HIGH\"}",true)));
    }
}
