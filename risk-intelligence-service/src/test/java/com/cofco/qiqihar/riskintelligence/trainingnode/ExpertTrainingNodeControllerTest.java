package com.cofco.qiqihar.riskintelligence.trainingnode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingNodeService;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExpertTrainingNodeControllerTest {
    @Test
    void cookieAndForgedRootHeaderCannotReplaceBearerToken() throws Exception {
        var service = mock(ExpertTrainingNodeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ExpertTrainingNodeController(
                service, mock(RemoteArtifactStore.class), "node-secret")).build();

        mvc.perform(post("/api/v1/risk-intelligence/training-node/expert-claims")
                        .cookie(new jakarta.servlet.http.Cookie("SESSION", "business"))
                        .header("X-Root-Administrator", "true")
                        .header("X-Risk-Training-Node-Id", "node-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeatReturnsCancellationAndRenewedLease() throws Exception {
        var service = mock(ExpertTrainingNodeService.class);
        UUID taskId = UUID.randomUUID();
        Instant leaseUntil = Instant.parse("2026-09-22T05:00:00Z");
        when(service.heartbeat(taskId, "node-a")).thenReturn(Optional.of(
                new ExpertTrainingRepository.Heartbeat(true, leaseUntil)));
        var mvc = MockMvcBuilders.standaloneSetup(new ExpertTrainingNodeController(
                service, mock(RemoteArtifactStore.class), "node-secret")).build();

        mvc.perform(post("/api/v1/risk-intelligence/training-node/expert-tasks/{taskId}/heartbeat", taskId)
                        .header("Authorization", "Bearer node-secret")
                        .header("X-Risk-Training-Node-Id", "node-a")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelRequested").value(true))
                .andExpect(jsonPath("$.leaseUntil").value(leaseUntil.toString()));
    }
}
