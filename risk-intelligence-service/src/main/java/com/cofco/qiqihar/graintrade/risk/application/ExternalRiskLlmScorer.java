package com.cofco.qiqihar.graintrade.risk.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExternalRiskLlmScorer implements RiskLiveScorerBackend {
    private final ObjectMapper json;
    private final HttpClient http;
    private final String endpoint;
    private final String bearerToken;

    @Autowired
    public ExternalRiskLlmScorer(ObjectMapper json,
            @Value("${qiqihar.risk.training.llm.scoring-endpoint:}") String endpoint,
            @Value("${qiqihar.risk.training.llm.bearer-token:}") String bearerToken) {
        this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                endpoint,bearerToken);
    }

    ExternalRiskLlmScorer(ObjectMapper json,HttpClient http,String endpoint,String bearerToken) {
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
    public RiskModelScore score(RiskScoringTask task) throws Exception {
        if (!supports(task.modelKind())) throw new IllegalStateException("独立大模型评分器未配置");
        byte[] body=json.writeValueAsBytes(Map.of(
                "modelId",task.modelId().toString(),"modelVersion",task.modelVersion(),
                "baseModelReference",task.baseModelReference(),
                "artifactReference",task.artifactReference(),
                "artifactSha256",task.artifactSha256(),"input",task.canonicalEvidence()));
        var builder=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofMinutes(5))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!bearerToken.isBlank()) builder.header("Authorization","Bearer "+bearerToken);
        HttpResponse<byte[]> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode()<200 || response.statusCode()>=300) {
            throw new IllegalStateException("独立大模型评分器返回 HTTP "+response.statusCode());
        }
        ScoreResponse result=json.readValue(response.body(),ScoreResponse.class);
        if (result.positiveProbability()<0d || result.positiveProbability()>1d) {
            throw new IllegalStateException("独立大模型评分概率不合法");
        }
        return new RiskModelScore(result.predictedPositive(),result.positiveProbability());
    }

    private boolean validEndpoint() {
        try {
            URI uri=URI.create(endpoint);
            return ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost()!=null && uri.getUserInfo()==null;
        } catch (IllegalArgumentException exception) { return false; }
    }

    private record ScoreResponse(boolean predictedPositive,double positiveProbability) { }
}
