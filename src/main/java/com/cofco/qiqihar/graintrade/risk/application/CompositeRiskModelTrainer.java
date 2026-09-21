package com.cofco.qiqihar.graintrade.risk.application;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CompositeRiskModelTrainer implements RiskModelTrainer {
    private final List<RiskTrainerBackend> backends;

    public CompositeRiskModelTrainer(List<RiskTrainerBackend> backends) {
        this.backends=List.copyOf(backends);
    }

    @Override
    public boolean supports(String modelKind) {
        return backends.stream().anyMatch(backend -> backend.supports(modelKind));
    }

    @Override
    public RiskTrainingArtifact train(RiskTrainingJob job) throws Exception {
        return backends.stream().filter(backend -> backend.supports(job.modelKind())).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "模型类型 %s 尚未配置独立训练器".formatted(job.modelKind())))
                .train(job);
    }
}
