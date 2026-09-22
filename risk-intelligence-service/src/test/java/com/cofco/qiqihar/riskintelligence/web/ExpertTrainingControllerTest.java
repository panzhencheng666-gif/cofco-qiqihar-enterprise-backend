package com.cofco.qiqihar.riskintelligence.web;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.riskintelligence.experttraining.ExpertDatasetValidator;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingController;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingRepository;
import com.cofco.qiqihar.riskintelligence.experttraining.ExpertTrainingService;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class ExpertTrainingControllerTest {
    @Test
    void overviewRequiresTrustedRootSessionAndReturnsStructuredError() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var service = new ExpertTrainingService(mock(ExpertTrainingRepository.class),
                new ExpertDatasetValidator(json), json, Clock.systemUTC());
        var mvc = MockMvcBuilders.standaloneSetup(new ExpertTrainingController(service))
                .setControllerAdvice(new RiskApiErrorHandler()).build();
        var regional = new RiskBusinessSession("regional", Set.of("BUSINESS_READ"), false,
                Set.of("230221"));

        mvc.perform(get("/api/v1/risk/expert-training/overview")
                        .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, regional)
                        .header("X-Root-Administrator", "true"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RISK_GLOBAL_MODEL_FORBIDDEN"));
    }
}
