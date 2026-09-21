package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LocalRiskClassifierScorerTest {
    @Test
    void loadsVerifiedTrainingArtifactAndScoresNewEvidence(@TempDir Path artifactRoot) throws Exception {
        ObjectMapper json=new ObjectMapper();
        var trainer=new LocalRiskClassifierTrainer(json,artifactRoot);
        var artifact=trainer.train(new RiskTrainingJob(
                UUID.randomUUID(),"risk-domain-classifier-v1","CROSS_DOMAIN",1,
                UUID.randomUUID(),7L,List.of(
                example("库存 短缺 高风险",true,1),
                example("库存 异常 高风险",true,2),
                example("运输 中断 高风险",true,3),
                example("库存 正常 稳定",false,4),
                example("供应 正常 稳定",false,5),
                example("运输 正常 稳定",false,6))));

        var scorer=new LocalRiskClassifierScorer(json);
        RiskModelScore risky=scorer.score(Path.of(artifact.artifactReference()),
                artifact.artifactSha256(),"库存 短缺 异常");
        RiskModelScore normal=scorer.score(Path.of(artifact.artifactReference()),
                artifact.artifactSha256(),"库存 正常 稳定");

        assertThat(risky.positiveProbability()).isGreaterThan(normal.positiveProbability());
        assertThat(risky.predictedPositive()).isTrue();
        assertThat(normal.predictedPositive()).isFalse();
    }

    private static RiskTrainingExample example(String text,boolean positive,int sequence) {
        return new RiskTrainingExample(UUID.randomUUID(),
                Instant.parse("2026-09-%02dT00:00:00Z".formatted(sequence)),text,positive);
    }
}
