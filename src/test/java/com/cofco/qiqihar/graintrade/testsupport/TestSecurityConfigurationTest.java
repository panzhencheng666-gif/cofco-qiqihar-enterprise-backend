package com.cofco.qiqihar.graintrade.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = TestSecurityConfigurationTest.ProbeController.class,
        useDefaultFilters = false,
        properties = "qiqihar.security.test-default-subject=")
@Import({TestSecurityConfigurationTest.ProbeController.class, TestSecurityConfiguration.class})
@ContextConfiguration(classes = GrainTradeApplication.class)
@ActiveProfiles("test")
class TestSecurityConfigurationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void explicitFakePrincipalCanAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").principal(() -> "test-subject"))
                .andExpect(status().isOk())
                .andExpect(content().string("test-subject"));
    }

    @Test
    void testProfileDoesNotPermitAllBusinessRequests() throws Exception {
        mockMvc.perform(get("/api/v1/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/whoami")
        String whoami(java.security.Principal principal) {
            return principal.getName();
        }
    }
}
