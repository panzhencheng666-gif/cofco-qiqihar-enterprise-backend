package com.cofco.qiqihar.graintrade.bootstrap;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@Import(HealthContractTest.ContractTestController.class)
@UsesProtectedTestDatabase
@ExtendWith(OutputCaptureExtension.class)
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
    void gatewayRequestIdBecomesTheApplicationTraceIdWhenNoTraceHeaderExists() throws Exception {
        mockMvc.perform(get("/_test/controlled-client-error")
                        .header("X-Request-Id", "gateway-request-123"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "gateway-request-123"))
                .andExpect(jsonPath("$.traceId").value("gateway-request-123"));
    }

    @Test
    void explicitSafeTraceIdWinsOverGatewayRequestId() throws Exception {
        mockMvc.perform(get("/_test/controlled-client-error")
                        .header("X-Trace-Id", "trace-wins")
                        .header("X-Request-Id", "gateway-loses"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "trace-wins"))
                .andExpect(jsonPath("$.traceId").value("trace-wins"));
    }

    @Test
    void invalidGatewayRequestIdNeverEntersTheResolvedTraceContract() throws Exception {
        String resolved = mockMvc.perform(get("/_test/controlled-client-error")
                        .header("X-Request-Id", "unsafe request value"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getHeader("X-Trace-Id");

        assertThat(resolved)
                .isNotEqualTo("unsafe request value")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void prometheusExportsStableInfrastructureAndBusinessMetricNames() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds")))
                .andExpect(content().string(containsString("qiqihar_database_pool_active")))
                .andExpect(content().string(containsString("qiqihar_database_pool_pending")))
                .andExpect(content().string(containsString("qiqihar_import_queue_active")))
                .andExpect(content().string(containsString("qiqihar_import_jobs_total")))
                .andExpect(content().string(containsString("qiqihar_report_generation_seconds")))
                .andExpect(content().string(containsString("qiqihar_business_event_backlog_seconds")))
                .andExpect(content().string(containsString("qiqihar_security_secret_key_ready")));
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
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.message").value("Method Not Allowed"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("wrong-method-test"));
    }

    @Test
    void unacceptableAcceptHeaderPreservesEmptyFrameworkResponse(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/_test/json-only")
                        .accept(MediaType.APPLICATION_XML)
                        .header("X-Trace-Id", "not-acceptable-test"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("X-Trace-Id", "not-acceptable-test"))
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().string(""));

        assertThat(output).doesNotContain("Failure in @ExceptionHandler");
    }

    @Test
    void frameworkInternalErrorsAreLoggedAndPreserveServletDiagnostics(CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/_test/framework-internal-error")
                        .header("X-Trace-Id", "framework-500-test"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Trace-Id", "framework-500-test"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Internal Server Error"))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.traceId").value("framework-500-test"))
                .andExpect(request().attribute(
                        RequestDispatcher.ERROR_EXCEPTION,
                        instanceOf(ConversionNotSupportedException.class)));

        assertThat(output).contains("Framework request failure [traceId=framework-500-test]");
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

        @GetMapping(path = "/_test/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> jsonOnly() {
            return Map.of("status", "ok");
        }

        @GetMapping("/_test/framework-internal-error")
        void failWithFrameworkInternalError() {
            throw new ConversionNotSupportedException(
                    "value",
                    String.class,
                    new IllegalStateException("sensitive framework failure detail"));
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
