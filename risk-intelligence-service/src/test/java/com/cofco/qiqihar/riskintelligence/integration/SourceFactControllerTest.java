package com.cofco.qiqihar.riskintelligence.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SourceFactControllerTest {
    private static final String BODY = """
            {
              "sourceSystem":"enterprise",
              "sourceRecordType":"BUSINESS_AUDIT",
              "sourceRecordId":"event-1",
              "sourceVersion":"v1",
              "businessOccurredAt":"2026-09-21T01:00:00Z",
              "payload":{"regionCode":"230221","actionCode":"SUBMITTED"}
            }
            """;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{}", "{\"regionCode\":null}", "{\"regionCode\":230221}",
            "{\"regionCode\":\"*\"}", "{\"regionCode\":\"230221%\"}"})
    void clientHeadersCannotSupplyOrRepairPayloadRegion(String payload) throws Exception {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        var service = new SourceFactIngestionService(repository, new tools.jackson.databind.ObjectMapper(),
                java.time.Clock.systemUTC(), "230221");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceFactController(service, "local-secret")).build();
        mvc.perform(post("/api/v1/risk-intelligence/source-facts")
                .header("X-Risk-Ingestion-Key", "local-secret")
                .header("X-Region-Code", "230221").header("X-Actor", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("{\"regionCode\":\"230221\",\"actionCode\":\"SUBMITTED\"}", payload)))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "230222"})
    void validMachineKeyCannotBypassConfiguredRegions(String configured) throws Exception {
        SourceFactRepository repository = mock(SourceFactRepository.class);
        var service = new SourceFactIngestionService(repository, new tools.jackson.databind.ObjectMapper(),
                java.time.Clock.systemUTC(), configured);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceFactController(service, "local-secret"))
                .setControllerAdvice(Class.forName("com.cofco.qiqihar.riskintelligence.web.RiskApiErrorHandler")).build();
        mvc.perform(post("/api/v1/risk-intelligence/source-facts")
                .header("X-Risk-Ingestion-Key", "local-secret").header("X-Region-Code", "230222")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void rejectsRequestsWithoutTheLocalIngestionCredential() throws Exception {
        SourceFactIngestionService ingestion = mock(SourceFactIngestionService.class);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SourceFactController(ingestion, "local-secret"))
                .build();

        mvc.perform(post("/api/v1/risk-intelligence/source-facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(ingestion, never()).ingest(any());
    }

    @Test
    void acceptsAnAuthenticatedSnapshotAndReturnsItsRealReceipt() throws Exception {
        SourceFactIngestionService ingestion = mock(SourceFactIngestionService.class);
        UUID snapshotId = UUID.randomUUID();
        when(ingestion.ingest(any())).thenReturn(new SourceFactReceipt(
                snapshotId, "a".repeat(64), Instant.parse("2026-09-21T06:00:00Z"), true));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SourceFactController(ingestion, "local-secret"))
                .build();

        mvc.perform(post("/api/v1/risk-intelligence/source-facts")
                        .header("X-Risk-Ingestion-Key", "local-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.created").value(true));
    }
}
