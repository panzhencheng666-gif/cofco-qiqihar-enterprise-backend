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
              "payload":{"actionCode":"SUBMITTED"}
            }
            """;

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
