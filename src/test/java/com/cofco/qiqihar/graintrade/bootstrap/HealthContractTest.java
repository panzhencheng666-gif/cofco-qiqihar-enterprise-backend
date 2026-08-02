package com.cofco.qiqihar.graintrade.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@Import(HealthContractTest.FailingTestController.class)
class HealthContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHealthReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void protocolErrorsUseUnifiedEnvelopeAndPreserveTraceId() throws Exception {
        mockMvc.perform(get("/_test/invalid-request")
                        .header("X-Trace-Id", "trace-contract-test"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "trace-contract-test"))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Invalid request"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("trace-contract-test"));
    }

    @RestController
    static class FailingTestController {

        @GetMapping("/_test/invalid-request")
        void fail() {
            throw new IllegalArgumentException("Invalid request");
        }
    }
}
