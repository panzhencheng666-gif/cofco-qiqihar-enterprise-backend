package com.cofco.qiqihar.riskintelligence.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantController;
import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantRepository;
import com.cofco.qiqihar.riskintelligence.assistant.AiAssistantService;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiAssistantControllerTest {
    @Test
    void createsAndReadsAQuestionUsingTheTrustedSession() throws Exception {
        UUID id = UUID.randomUUID();
        var repository = mock(AiAssistantRepository.class);
        var service = mock(AiAssistantService.class);
        var session = new RiskBusinessSession(
                "employee-1", Set.of("BUSINESS_READ", "BUSINESS_UPDATE"), false, Set.of("230200"));
        var view = new AiAssistantRepository.RequestView(
                id, "employee-1", "QUEUED", "玉米水分标准是什么？", null, null,
                null, null, null, null, null, Instant.parse("2026-09-22T14:30:00Z"), null);
        when(service.ask(eq("玉米水分标准是什么？"), eq("assistant-request-1"), any()))
                .thenReturn(view);
        when(service.get(eq(id), any())).thenReturn(view);
        var mvc = MockMvcBuilders.standaloneSetup(new AiAssistantController(service))
                .setControllerAdvice(new RiskApiErrorHandler()).build();

        mvc.perform(post("/api/v1/risk/assistant/questions")
                        .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, session)
                        .header("Idempotency-Key", "assistant-request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"玉米水分标准是什么？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.requestId").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        mvc.perform(get("/api/v1/risk/assistant/questions/{id}", id)
                        .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.question").value("玉米水分标准是什么？"));
    }
}
