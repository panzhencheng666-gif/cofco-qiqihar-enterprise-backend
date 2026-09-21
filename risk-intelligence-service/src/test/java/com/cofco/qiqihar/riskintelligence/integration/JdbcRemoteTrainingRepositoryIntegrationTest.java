package com.cofco.qiqihar.riskintelligence.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingArtifact;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingClaim;
import com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RISK_DB_URL", matches = ".+")
class JdbcRemoteTrainingRepositoryIntegrationTest {
    @Autowired
    private RiskTrainingRepository repository;

    @Test
    void remoteLeaseQueriesExecuteAgainstTheRealRiskSchemaWithoutTouchingUnknownWork() {
        Instant now=Instant.parse("2026-09-21T03:00:00Z");
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();

        assertThat(repository.claimNextByKind(now,"integration-probe",Duration.ofMinutes(5),
                "__NO_SUCH_MODEL_KIND__")).isEmpty();
        RiskTrainingClaim probe=new RiskTrainingClaim(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"probe","probe","DOMAIN_LLM","__NO_SUCH_DOMAIN__",
                "probe",30,1,7L);
        assertThat(repository.countNewLabelsSinceLastSuccessfulRun(probe,now)).isGreaterThanOrEqualTo(0);
        assertThat(repository.renewRemoteLease(executionId,runId,"integration-probe",now,
                Duration.ofMinutes(5))).isFalse();
        assertThat(repository.ownsRemoteLease(
                executionId,runId,"integration-probe",now)).isFalse();
        assertThat(repository.completeRemoteRun(executionId,runId,"integration-probe",1,
                new RiskTrainingArtifact("risk-artifact://integration-probe","a".repeat(64),
                        Map.of("probe",1.0d),Map.of("probe",1.0d),
                        "integration-probe","1","RETRAIN"),now)).isFalse();
    }
}
