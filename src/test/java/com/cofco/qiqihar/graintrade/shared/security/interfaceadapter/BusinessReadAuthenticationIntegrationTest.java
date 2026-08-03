package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = GrainTradeApplication.class,
        properties = {
            "qiqihar.security.require-read-authentication=true",
            "qiqihar.security.trusted-subject-header=X-Qiqihar-Authenticated-Subject"
        })
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class BusinessReadAuthenticationIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void rejectsUnauthenticatedBusinessReadsBeforeTheControllerExecutes() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsAuthenticatedSubjectsThatAreNotProvisionedForBusinessReads() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/products")
                        .header("X-Qiqihar-Authenticated-Subject", "unknown-reader"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_SUBJECT_UNKNOWN"));
    }
}
