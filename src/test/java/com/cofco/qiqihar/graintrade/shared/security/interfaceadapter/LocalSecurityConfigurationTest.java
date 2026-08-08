package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

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
        controllers = LocalSecurityConfigurationTest.ProbeController.class,
        useDefaultFilters = false,
        properties = {
            "server.address=127.0.0.1",
            "qiqihar.security.trusted-subject-header=X-Actor"
        })
@Import({LocalSecurityConfigurationTest.ProbeController.class,
        LocalSecurityConfiguration.class, SecurityStartupInvariant.class})
@ContextConfiguration(classes = GrainTradeApplication.class)
@ActiveProfiles("local")
class LocalSecurityConfigurationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void localHeaderBecomesServletPrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").header("X-Actor", "local-subject"))
                .andExpect(status().isOk())
                .andExpect(content().string("local-subject"));
    }

    @Test
    void localBusinessApiStillRejectsMissingActor() throws Exception {
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
