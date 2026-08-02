package com.cofco.qiqihar.graintrade.workflow.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class WorkItemRestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsAnEmptyCanonicalServerPageForAnEmptyProductionTable() throws Exception {
        mockMvc.perform(get("/api/v1/work-items")
                        .queryParam("scope", "PENDING")
                        .queryParam("page", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.pageNumber").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void rejectsUnknownRepeatedBlankAndIllegalQueriesWithOneControlledCode() throws Exception {
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING")
                .queryParam("page", "0")
                .queryParam("pageSize", "20")
                .queryParam("pageNubmer", "1"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING", "COMPLETED")
                .queryParam("page", "0")
                .queryParam("pageSize", "20"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING")
                .queryParam("status", " ")
                .queryParam("page", "0")
                .queryParam("pageSize", "20"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "COMPLETED")
                .queryParam("status", "TO_FILL")
                .queryParam("page", "0")
                .queryParam("pageSize", "20"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING")
                .queryParam("page", "abc")
                .queryParam("pageSize", "20"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING")
                .queryParam("domain", "UNKNOWN")
                .queryParam("page", "0")
                .queryParam("pageSize", "20"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING")
                .queryParam("regionId", "999999")
                .queryParam("page", "0")
                .queryParam("pageSize", "20"));
        assertInvalid(get("/api/v1/work-items")
                .queryParam("scope", "PENDING")
                .queryParam("productCode", "UNKNOWN")
                .queryParam("page", "0")
                .queryParam("pageSize", "20"));
    }

    @Test
    void returnsAConsistentEmptyPageForTheLargestSupportedPageNumber() throws Exception {
        mockMvc.perform(get("/api/v1/work-items")
                        .queryParam("scope", "PENDING")
                        .queryParam("page", String.valueOf(Integer.MAX_VALUE))
                        .queryParam("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.pageNumber").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    private void assertInvalid(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_WORK_ITEM_QUERY"));
    }
}
