package com.cofco.qiqihar.graintrade.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class LocalRiskClassifierTrainerTest {

    @TempDir Path artifactRoot;

    @Test
    void producesAReproducibleRealArtifactAndEvaluationMetrics() throws Exception {
        UUID modelId=UUID.fromString("21500000-0000-0000-0000-000000000001");
        UUID snapshotId=UUID.fromString("21500000-0000-0000-0000-000000000101");
        var examples=List.of(
                example(1,"库存 水分升高 出库异常",true),
                example(2,"库存 账实不符 重复出库",true),
                example(3,"库存 正常 复核一致",false),
                example(4,"库存 正常 无异常",false),
                example(5,"库存 霉变 高风险",true),
                example(6,"库存 正常 凭证一致",false));
        var job=new RiskTrainingJob(modelId,"risk-domain-classifier-v1","CROSS_DOMAIN",1,
                snapshotId,7L,examples);
        var trainer=new LocalRiskClassifierTrainer(new ObjectMapper(),artifactRoot);

        RiskTrainingArtifact first=trainer.train(job);
        RiskTrainingArtifact second=trainer.train(job);

        assertThat(first.artifactSha256()).matches("[0-9a-f]{64}");
        assertThat(first.artifactSha256()).isEqualTo(second.artifactSha256());
        assertThat(first.artifactReference()).isEqualTo(second.artifactReference());
        assertThat(Files.readString(Path.of(first.artifactReference())))
                .contains("bernoulli-naive-bayes","risk-domain-classifier-v1","positiveTokenCounts");
        assertThat(first.metrics()).containsKeys(
                "exampleCount","validationCount","accuracy","precision","recall","f1");
        assertThat(first.thresholds()).containsEntry("positiveProbability",0.5d);
    }

    @Test
    void preservesBothTrainingClassesWhenTheNewestLabelIsTheOnlyNegative(@TempDir Path root)
            throws Exception {
        var examples=List.of(
                example(1,"库存 异常",true),example(2,"库存 霉变",true),
                example(3,"库存 超限",true),example(4,"库存 账实不符",true),
                example(5,"库存 正常",false));
        var trainer=new LocalRiskClassifierTrainer(new ObjectMapper(),root);

        RiskTrainingArtifact artifact=trainer.train(new RiskTrainingJob(UUID.randomUUID(),
                "chronological-model","CROSS_DOMAIN",1,UUID.randomUUID(),11L,examples));

        assertThat(artifact.metrics()).containsEntry("trainingCount",4);
        assertThat(Files.readString(Path.of(artifact.artifactReference())))
                .contains("\"negativeDocuments\":1");
    }

    private static RiskTrainingExample example(int ordinal,String text,boolean positive) {
        return new RiskTrainingExample(UUID.nameUUIDFromBytes(("assessment-"+ordinal).getBytes()),
                Instant.parse("2026-09-%02dT01:00:00Z".formatted(ordinal)),text,positive);
    }
}
