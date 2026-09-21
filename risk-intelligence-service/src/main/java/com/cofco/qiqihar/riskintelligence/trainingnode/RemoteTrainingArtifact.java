package com.cofco.qiqihar.riskintelligence.trainingnode;

import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingArtifact;
import java.net.URI;
import java.util.Map;

public record RemoteTrainingArtifact(
        String artifactReference,
        String artifactSha256,
        Map<String,Object> metrics,
        Map<String,Object> thresholds) {

    public RemoteTrainingArtifact {
        URI uri=URI.create(artifactReference==null?"":artifactReference);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "oss".equalsIgnoreCase(uri.getScheme())
                || "risk-artifact".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost()==null || uri.getUserInfo()!=null) {
            throw new IllegalArgumentException("训练工件必须使用云端 HTTPS、OSS 或受管工件地址");
        }
        if (artifactSha256==null || !artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("训练工件 SHA-256 不合法");
        }
        metrics=metrics==null?Map.of():Map.copyOf(metrics);
        thresholds=thresholds==null?Map.of():Map.copyOf(thresholds);
    }

    RiskTrainingArtifact toArtifact() {
        return new RiskTrainingArtifact(artifactReference,artifactSha256,metrics,thresholds,
                "mlx-lm-lora","1","LORA_ADAPTER");
    }
}
