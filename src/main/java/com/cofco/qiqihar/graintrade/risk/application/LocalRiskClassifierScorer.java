package com.cofco.qiqihar.graintrade.risk.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalRiskClassifierScorer {
    private static final Pattern WORD=Pattern.compile("[\\p{IsHan}]{1,4}|[\\p{L}\\p{N}_-]{2,}");
    private final ObjectMapper json;

    public LocalRiskClassifierScorer(ObjectMapper json) {
        this.json=json;
    }

    public RiskModelScore score(Path artifact,String expectedSha256,String canonicalEvidence)
            throws Exception {
        byte[] bytes=Files.readAllBytes(artifact);
        String actual=HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("模型工件哈希校验失败");
        }
        JsonNode root=json.readTree(bytes);
        if (!"bernoulli-naive-bayes".equals(root.path("algorithm").asText())) {
            throw new IllegalArgumentException("不支持的本地模型工件算法");
        }
        int positiveDocuments=root.path("positiveDocuments").asInt();
        int negativeDocuments=root.path("negativeDocuments").asInt();
        Set<String> present=tokens(canonicalEvidence);
        double positiveLog=Math.log((positiveDocuments+1d)/(positiveDocuments+negativeDocuments+2d));
        double negativeLog=Math.log((negativeDocuments+1d)/(positiveDocuments+negativeDocuments+2d));
        for (JsonNode item:root.path("vocabulary")) {
            String token=item.asText();
            int positiveCount=root.path("positiveTokenCounts").path(token).asInt(0);
            int negativeCount=root.path("negativeTokenCounts").path(token).asInt(0);
            double positiveChance=(positiveCount+1d)/(positiveDocuments+2d);
            double negativeChance=(negativeCount+1d)/(negativeDocuments+2d);
            positiveLog+=Math.log(present.contains(token)?positiveChance:1d-positiveChance);
            negativeLog+=Math.log(present.contains(token)?negativeChance:1d-negativeChance);
        }
        double maximum=Math.max(positiveLog,negativeLog);
        double positive=Math.exp(positiveLog-maximum);
        double negative=Math.exp(negativeLog-maximum);
        double probability=positive/(positive+negative);
        double threshold=root.path("thresholds").path("positiveProbability").asDouble(0.5d);
        return new RiskModelScore(probability>=threshold,probability);
    }

    private static Set<String> tokens(String text) {
        if (text==null || text.isBlank()) return Set.of();
        Set<String> result=new HashSet<>();
        var matcher=WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
}
