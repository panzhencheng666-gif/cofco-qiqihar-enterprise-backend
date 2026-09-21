package com.cofco.qiqihar.graintrade.risk.application;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CompositeRiskLiveScorer implements RiskLiveScorer {
    private final List<RiskLiveScorerBackend> backends;

    public CompositeRiskLiveScorer(List<RiskLiveScorerBackend> backends) {
        this.backends=List.copyOf(backends);
    }

    @Override
    public boolean supports(String modelKind) {
        return backends.stream().anyMatch(backend -> backend.supports(modelKind));
    }

    @Override
    public RiskModelScore score(RiskScoringTask task) throws Exception {
        return backends.stream().filter(backend -> backend.supports(task.modelKind())).findFirst()
                .orElseThrow(() -> new IllegalStateException("模型类型没有实时评分器: "+task.modelKind()))
                .score(task);
    }
}
