package com.cofco.qiqihar.riskintelligence.trainingnode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantNodeService;
import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiAssistantNodeControllerTest {
    @Test
    void protectsClaimsAndCompletionWithTheExistingNodeCredential() throws Exception {
        UUID id = UUID.randomUUID();
        var service = mock(AiAssistantNodeService.class);
        when(service.claim("mac-node")).thenReturn(Optional.of(new AiAssistantRepository.NodeClaim(
                id, "玉米水分标准是什么？", Instant.parse("2026-09-22T14:33:00Z"), 1)));
        var mvc = MockMvcBuilders.standaloneSetup(
                new AiAssistantNodeController(service, "node-secret")).build();

        mvc.perform(post("/api/v1/risk-intelligence/training-node/assistant-claims"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/risk-intelligence/training-node/assistant-claims")
                        .header("Authorization", "Bearer node-secret")
                        .header("X-Risk-Training-Node-Id", "mac-node"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(id.toString()))
                .andExpect(jsonPath("$.question").value("玉米水分标准是什么？"));

        var response = """
                {"status":"ANSWERED","mode":"FOUNDATION_RAG","modelReference":"local-model",
                 "knowledgeVersion":"v1","answer":"回答","citations":[{"id":"s1"}],
                 "limitations":["限制"]}
                """;
        when(service.complete(org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq("mac-node"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        mvc.perform(post("/api/v1/risk-intelligence/training-node/assistant-requests/{id}/completion", id)
                        .header("Authorization", "Bearer node-secret")
                        .header("X-Risk-Training-Node-Id", "mac-node")
                        .contentType(MediaType.APPLICATION_JSON).content(response))
                .andExpect(status().isNoContent());
    }
}
