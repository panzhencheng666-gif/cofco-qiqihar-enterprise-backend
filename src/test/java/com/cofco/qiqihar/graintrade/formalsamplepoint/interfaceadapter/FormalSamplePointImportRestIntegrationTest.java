package com.cofco.qiqihar.graintrade.formalsamplepoint.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class FormalSamplePointImportRestIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void publishesARealXlsxTemplate() throws Exception {
        mvc.perform(get("/api/v1/formal-sample-points/import-template")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));
    }
}
