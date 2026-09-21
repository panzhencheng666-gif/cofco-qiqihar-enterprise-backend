package com.cofco.qiqihar.graintrade.risk.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExternalLoraRiskModelTrainer implements RiskTrainerBackend {
    private final ObjectMapper json;
    private final HttpClient http;
    private final String endpoint;
    private final String bearerToken;

    @Autowired
    public ExternalLoraRiskModelTrainer(ObjectMapper json,
            @Value("${qiqihar.risk.training.llm.endpoint:}") String endpoint,
            @Value("${qiqihar.risk.training.llm.bearer-token:}") String bearerToken) {
        this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                endpoint,bearerToken);
    }

    ExternalLoraRiskModelTrainer(ObjectMapper json,HttpClient http,String endpoint,String bearerToken) {
        this.json=json;
        this.http=http;
        this.endpoint=endpoint==null?"":endpoint.strip();
        this.bearerToken=bearerToken==null?"":bearerToken.strip();
    }

    @Override
    public boolean supports(String modelKind) {
        return "DOMAIN_LLM".equals(modelKind) && validEndpoint();
    }

    @Override
    public RiskTrainingArtifact train(RiskTrainingJob job) throws Exception {
        if (!supports(job.modelKind())) throw new IllegalStateException("独立大模型 LoRA 训练器未配置");
        URI uri=URI.create(endpoint);
        List<Map<String,Object>> examples=job.examples().stream().map(example -> Map.<String,Object>of(
                "resolvedAt",example.resolvedAt().toString(),
                "input",example.canonicalText(),
                "positive",example.positive())).toList();
        byte[] body=json.writeValueAsBytes(Map.of(
                "modelId",job.modelId().toString(),"modelCode",job.modelCode(),
                "baseModelReference",job.baseModelReference(),"domainCode",job.domainCode(),
                "candidateVersion",job.modelVersion(),
                "trainingSnapshotId",job.trainingSnapshotId().toString(),
                "randomSeed",job.randomSeed(),"trainingKind","LORA_ADAPTER",
                "examples",examples));
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!bearerToken.isBlank()) builder.header("Authorization","Bearer "+bearerToken);
        HttpResponse<byte[]> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode()<200 || response.statusCode()>=300) {
            throw new IllegalStateException("独立大模型训练器返回 HTTP "+response.statusCode());
        }
        TrainerResponse result=json.readValue(response.body(),TrainerResponse.class);
        if (result.artifactReference()==null || result.artifactReference().isBlank()
                || result.artifactSha256()==null
                || !result.artifactSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("独立大模型训练器未返回可验证工件和 SHA-256");
        }
        return new RiskTrainingArtifact(result.artifactReference(),result.artifactSha256(),
                result.metrics()==null?Map.of():result.metrics(),
                result.thresholds()==null?Map.of():result.thresholds(),
                "external-lora-adapter-v1","1","LORA_ADAPTER");
    }

    private record TrainerResponse(String artifactReference,String artifactSha256,
            Map<String,Object> metrics,Map<String,Object> thresholds) { }

    private boolean validEndpoint() {
        try {
            URI uri=URI.create(endpoint);
            return ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost()!=null && uri.getUserInfo()==null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
