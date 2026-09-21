package com.cofco.qiqihar.riskintelligence.trainingnode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrainingNodeControllerTest {
    private static final String TOKEN="node-secret";
    private static final String NODE="mac-m5-max";

    @Test
    void rejectsAClaimWithoutTheTrainingNodeCredential() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        MockMvc mvc=mvc(coordinator);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/claims")
                        .header("X-Risk-Training-Node-Id",NODE))
                .andExpect(status().isUnauthorized());

        verify(coordinator,never()).claimNext(NODE);
    }

    @Test
    void returnsNoContentWhenTheCloudQueueHasNoEligibleTraining() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        when(coordinator.claimNext(NODE)).thenReturn(Optional.empty());
        MockMvc mvc=mvc(coordinator);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/claims")
                        .header("Authorization","Bearer "+TOKEN)
                        .header("X-Risk-Training-Node-Id",NODE))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsTheImmutableTrainingSnapshotToAnAuthenticatedNode() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        UUID modelId=UUID.randomUUID();
        UUID snapshotId=UUID.randomUUID();
        when(coordinator.claimNext(NODE)).thenReturn(Optional.of(new RemoteTrainingJob(
                executionId,runId,modelId,"risk-reasoning-llm-v1","DOMAIN_LLM",
                "CROSS_DOMAIN","mlx-community/Qwen3-0.6B-4bit",3,snapshotId,
                "a".repeat(64),7L,Instant.parse("2026-09-21T18:00:00Z"),
                List.of(new RemoteTrainingExample(
                        Instant.parse("2026-09-21T01:00:00Z"),"真实证据",true)))));
        MockMvc mvc=mvc(coordinator);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/claims")
                        .header("Authorization","Bearer "+TOKEN)
                        .header("X-Risk-Training-Node-Id",NODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.trainingRunId").value(runId.toString()))
                .andExpect(jsonPath("$.trainingSnapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.dataSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.examples[0].input").value("真实证据"));
    }

    @Test
    void renewsOnlyTheLeaseOwnedByTheAuthenticatedNode() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        when(coordinator.renewLease(NODE,executionId,runId)).thenReturn(true);
        MockMvc mvc=mvc(coordinator);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/executions/{executionId}/heartbeat",
                        executionId)
                        .header("Authorization","Bearer "+TOKEN)
                        .header("X-Risk-Training-Node-Id",NODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainingRunId\":\""+runId+"\"}"))
                .andExpect(status().isNoContent());

        verify(coordinator).renewLease(NODE,executionId,runId);
    }

    @Test
    void persistsACloudReachableArtifactOnlyForTheOwningNode() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        RemoteArtifactStore artifacts=mock(RemoteArtifactStore.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        String artifactReference="risk-artifact://sha256/"+"c".repeat(64);
        when(artifacts.matches(runId,artifactReference,"b".repeat(64))).thenReturn(true);
        when(coordinator.complete(NODE,executionId,runId,3,
                new RemoteTrainingArtifact(artifactReference,"b".repeat(64),
                        Map.of("f1",0.82d),Map.of("positiveProbability",0.5d))))
                .thenReturn(true);
        MockMvc mvc=mvc(coordinator,artifacts);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/executions/{executionId}/completion",
                        executionId)
                        .header("Authorization","Bearer "+TOKEN)
                        .header("X-Risk-Training-Node-Id",NODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "trainingRunId":"%s",
                                  "modelVersion":3,
                                  "artifactReference":"%s",
                                  "artifactSha256":"%s",
                                  "metrics":{"f1":0.82},
                                  "thresholds":{"positiveProbability":0.5}
                                }
                                """.formatted(runId,artifactReference,"b".repeat(64))))
                .andExpect(status().isNoContent());
    }

    @Test
    void storesAnAuthenticatedImmutableAdapterBundleBeforeCompletion() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        RemoteArtifactStore artifacts=mock(RemoteArtifactStore.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        byte[] bundle="real-adapter-bundle".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String bundleHash="c".repeat(64);
        String contentHash="d".repeat(64);
        when(coordinator.ownsLease(NODE,executionId,runId)).thenReturn(true);
        when(artifacts.store(eq(runId),eq(bundleHash),eq(contentHash),
                any(java.io.InputStream.class),eq((long) bundle.length)))
                .thenReturn(new StoredRemoteArtifact(
                        "risk-artifact://sha256/"+bundleHash,bundleHash,contentHash,bundle.length));
        MockMvc mvc=mvc(coordinator,artifacts);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/artifacts")
                        .header("Authorization","Bearer "+TOKEN)
                        .header("X-Risk-Training-Node-Id",NODE)
                        .header("X-Risk-Training-Execution-Id",executionId.toString())
                        .header("X-Risk-Training-Run-Id",runId.toString())
                        .header("X-Risk-Artifact-Sha256",bundleHash)
                        .header("X-Risk-Artifact-Content-Sha256",contentHash)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bundle))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.artifactReference")
                        .value("risk-artifact://sha256/"+bundleHash))
                .andExpect(jsonPath("$.sizeBytes").value(bundle.length));
    }

    @Test
    void rejectsAnArtifactUploadOutsideTheNodesOwnedLease() throws Exception {
        RemoteTrainingCoordinator coordinator=mock(RemoteTrainingCoordinator.class);
        RemoteArtifactStore artifacts=mock(RemoteArtifactStore.class);
        UUID executionId=UUID.randomUUID();
        UUID runId=UUID.randomUUID();
        MockMvc mvc=mvc(coordinator,artifacts);

        mvc.perform(post("/api/v1/risk-intelligence/training-node/artifacts")
                        .header("Authorization","Bearer "+TOKEN)
                        .header("X-Risk-Training-Node-Id",NODE)
                        .header("X-Risk-Training-Execution-Id",executionId.toString())
                        .header("X-Risk-Training-Run-Id",runId.toString())
                        .header("X-Risk-Artifact-Sha256","c".repeat(64))
                        .header("X-Risk-Artifact-Content-Sha256","d".repeat(64))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("unowned"))
                .andExpect(status().isConflict());

        verify(artifacts,never()).store(any(),anyString(),anyString(),
                any(java.io.InputStream.class),anyLong());
    }

    private static MockMvc mvc(RemoteTrainingCoordinator coordinator) {
        return mvc(coordinator,mock(RemoteArtifactStore.class));
    }

    private static MockMvc mvc(
            RemoteTrainingCoordinator coordinator,RemoteArtifactStore artifacts) {
        return MockMvcBuilders.standaloneSetup(
                new TrainingNodeController(coordinator,mock(RemoteScoringCoordinator.class),
                        artifacts,TOKEN)).build();
    }
}
