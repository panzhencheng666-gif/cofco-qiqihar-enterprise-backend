package com.cofco.qiqihar.riskintelligence.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class RiskBusinessSessionFilterTest {
    @Test
    void rejectsRequestsWithoutAValidatedBusinessSession() throws Exception {
        MockMvc mvc = mvc(cookie -> Optional.empty());

        mvc.perform(get("/api/v1/risk/test").header("X-Actor", "administrator"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ignoresForgedActorAndUsesTheValidatedSessionSubject() throws Exception {
        AtomicReference<String> forwardedCookie = new AtomicReference<>();
        MockMvc mvc = mvc(cookie -> {
            forwardedCookie.set(cookie);
            return Optional.of(new RiskBusinessSession(
                    "real-employee", Set.of("BUSINESS_READ"), false, Set.of("230221")));
        });

        mvc.perform(get("/api/v1/risk/test")
                        .header("Cookie", "COFCO_SESSION=validated")
                        .header("X-Actor", "administrator"))
                .andExpect(status().isOk())
                .andExpect(content().string("real-employee"));

        assertThat(forwardedCookie.get()).isEqualTo("COFCO_SESSION=validated");
    }

    @Test
    void requiresReadPermissionForQueries() throws Exception {
        MockMvc mvc = mvc(cookie -> Optional.of(new RiskBusinessSession(
                "employee", Set.of("BUSINESS_CREATE"), false, Set.of("230221"))));

        mvc.perform(get("/api/v1/risk/test").header("Cookie", "COFCO_SESSION=validated"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requiresUpdatePermissionForMutations() throws Exception {
        MockMvc mvc = mvc(cookie -> Optional.of(new RiskBusinessSession(
                "reader", Set.of("BUSINESS_READ"), false, Set.of("230221"))));

        mvc.perform(post("/api/v1/risk/test").header("Cookie", "COFCO_SESSION=validated"))
                .andExpect(status().isForbidden());
    }

    @Test
    void failsClosedWhenTheBusinessSessionServiceIsUnavailable() throws Exception {
        MockMvc mvc = mvc(cookie -> {
            throw new RiskSessionValidationUnavailableException("unavailable");
        });

        mvc.perform(get("/api/v1/risk/test").header("Cookie", "COFCO_SESSION=validated"))
                .andExpect(status().isServiceUnavailable());
    }

    private static MockMvc mvc(RiskBusinessSessionClient client) {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new RiskBusinessSessionFilter(client))
                .build();
    }

    @RestController
    @RequestMapping("/api/v1/risk/test")
    private static class ProbeController {
        @GetMapping
        String read(jakarta.servlet.http.HttpServletRequest request) {
            return RiskBusinessSession.require(request).subjectId();
        }

        @PostMapping
        String write(jakarta.servlet.http.HttpServletRequest request) {
            return RiskBusinessSession.require(request).subjectId();
        }
    }
}
