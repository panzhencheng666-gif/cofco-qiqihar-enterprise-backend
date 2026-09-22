package com.cofco.qiqihar.riskintelligence.experttraining;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpertTrainingServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpertTrainingRepository repository = mock(ExpertTrainingRepository.class);
    private final ExpertTrainingService service = new ExpertTrainingService(repository,
            new ExpertDatasetValidator(mapper), mapper,
            Clock.fixed(Instant.parse("2026-09-22T03:00:00Z"), ZoneOffset.UTC));

    @Test
    void rejectsRegionalBusinessSessionEvenWithBusinessUpdatePermission() throws Exception {
        var regional = new RiskBusinessSession("regional", Set.of("BUSINESS_UPDATE"), false,
                Set.of("230221"));

        assertThatThrownBy(() -> service.createDataset(mapper.readTree(
                ExpertDatasetValidatorTest.fixture()), regional))
                .isInstanceOf(RiskApiException.class)
                .extracting(error -> ((RiskApiException) error).code())
                .isEqualTo("RISK_GLOBAL_MODEL_FORBIDDEN");
        verifyNoInteractions(repository);
    }

    @Test
    void taskUsesFixedModelAndRejectsInvalidConfigBeforePersistence() throws Exception {
        var root = new RiskBusinessSession("root", Set.of(), true, Set.of());
        UUID snapshotId = UUID.randomUUID();
        var request = mapper.readTree("""
                {"datasetSnapshotId":"%s","idempotencyKey":"synthetic-key",
                 "config":{"iterations":10,"maxSeqLength":1024,"numLayers":4,"seed":7,
                           "learningRate":0.00001}}
                """.formatted(snapshotId));
        when(repository.requestTask(eq(snapshotId), eq("root"), eq("synthetic-key"),
                any(), any(), eq("qiliang-risk-llm-v1"), any()))
                .thenReturn(mock(ExpertTrainingRepository.TaskView.class));
        service.createTask(request, root);

        var invalid = request.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) invalid.path("config"))
                .put("learningRate", Double.NaN);
        assertThatThrownBy(() -> service.createTask(invalid, root))
                .isInstanceOf(ExpertDatasetValidationException.class);
    }
}
