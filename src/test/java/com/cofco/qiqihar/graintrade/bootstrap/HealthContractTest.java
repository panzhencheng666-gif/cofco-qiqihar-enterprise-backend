package com.cofco.qiqihar.graintrade.bootstrap;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@Import(HealthContractTest.ContractTestController.class)
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
    void controlledClientErrorsUseStableEnvelopeAndPreserveTraceId() throws Exception {
        mockMvc.perform(get("/_test/controlled-client-error")
                        .header("X-Trace-Id", "trace-contract-test"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "trace-contract-test"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("The request cannot be processed"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("trace-contract-test"));
    }

    @Test
    void missingRequiredParameterUsesUnifiedClientErrorEnvelope() throws Exception {
        mockMvc.perform(get("/_test/required-parameter")
                        .header("X-Trace-Id", "missing-parameter-test"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "missing-parameter-test"))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Bad Request"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("missing-parameter-test"));
    }

    @Test
    void unsupportedMethodUsesUnifiedClientErrorEnvelope() throws Exception {
        mockMvc.perform(post("/_test/required-parameter")
                        .param("value", "present")
                        .header("X-Trace-Id", "wrong-method-test"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("X-Trace-Id", "wrong-method-test"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.message").value("Method Not Allowed"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("wrong-method-test"));
    }

    @Test
    void ordinaryIllegalArgumentsAreSafeInternalErrors() throws Exception {
        mockMvc.perform(get("/_test/ordinary-illegal-argument")
                        .header("X-Trace-Id", "ordinary-iae-test"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Trace-Id", "ordinary-iae-test"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("ordinary-iae-test"));
    }

    @Test
    void emptyIllegalArgumentMessagesDoNotCauseSecondaryFailures() throws Exception {
        mockMvc.perform(get("/_test/empty-illegal-argument")
                        .header("X-Trace-Id", "empty-iae-test"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Trace-Id", "empty-iae-test"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("empty-iae-test"));
    }

    @RestController
    static class ContractTestController {

        @GetMapping("/_test/controlled-client-error")
        void failWithControlledClientError() {
            throw new ClientRequestException(
                    "INVALID_REQUEST",
                    "The request cannot be processed");
        }

        @GetMapping("/_test/required-parameter")
        String requiresParameter(@RequestParam String value) {
            return value;
        }

        @GetMapping("/_test/ordinary-illegal-argument")
        void failWithOrdinaryIllegalArgument() {
            throw new IllegalArgumentException("sensitive implementation detail");
        }

        @GetMapping("/_test/empty-illegal-argument")
        void failWithEmptyIllegalArgument() {
            throw new IllegalArgumentException();
        }
    }
}
