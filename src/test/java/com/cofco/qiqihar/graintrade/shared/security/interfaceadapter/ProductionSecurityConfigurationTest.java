package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = ProductionSecurityConfigurationTest.ProbeController.class,
        useDefaultFilters = false,
        properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test")
@Import({ProductionSecurityConfigurationTest.ProbeController.class,
        ProductionSecurityConfiguration.class, SecurityStartupInvariant.class,
        ProductionSecurityConfigurationTest.DecoderConfiguration.class})
@ContextConfiguration(classes = GrainTradeApplication.class)
@ActiveProfiles("production")
class ProductionSecurityConfigurationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void actuatorHealthIsAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedBusinessApiIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgedActorHeaderDoesNotAuthenticateBusinessApi() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").header("X-Actor", "forged-subject"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwtSubjectIsTheServletPrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").with(jwt().jwt(token -> token.subject("oidc-subject"))))
                .andExpect(status().isOk())
                .andExpect(content().string("oidc-subject"));
    }

    @Test
    void authenticatedProductionAsyncDispatchCompletesWithoutReauthorizationFailure() throws Exception {
        MvcResult initial = mockMvc.perform(get("/api/v1/whoami-async")
                        .with(jwt().jwt(token -> token.subject("oidc-subject"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string("oidc-subject"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DecoderConfiguration {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("MockMvc supplies the authenticated JWT");
            };
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/api/v1/whoami")
        String whoami(java.security.Principal principal) {
            return principal.getName();
        }

        @GetMapping("/api/v1/whoami-async")
        java.util.concurrent.Callable<String> whoamiAsync(java.security.Principal principal) {
            return principal::getName;
        }
    }
}
