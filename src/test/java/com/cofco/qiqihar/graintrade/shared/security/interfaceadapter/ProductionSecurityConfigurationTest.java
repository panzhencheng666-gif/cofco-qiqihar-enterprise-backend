package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = ProductionSecurityConfigurationTest.ProbeController.class,
        useDefaultFilters = false,
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
                "qiqihar.security.oidc.client-id=test-client",
                "qiqihar.security.oidc.client-secret=test-secret",
                "qiqihar.security.oidc.authorization-uri=https://issuer.example.test/authorize",
                "qiqihar.security.oidc.token-uri=https://issuer.example.test/token",
                "qiqihar.security.oidc.jwk-set-uri=https://issuer.example.test/jwks",
                "qiqihar.security.oidc.end-session-uri=https://issuer.example.test/logout",
                "qiqihar.security.oidc.redirect-uri=https://app.example.test/login/oauth2/code/enterprise",
                "qiqihar.security.oidc.post-logout-redirect-uri=https://app.example.test/logged-out",
                "qiqihar.security.oidc.mfa-amr-values=mfa,otp"
        })
@Import({ProductionSecurityConfigurationTest.ProbeController.class,
        OidcLoginController.class, ProductionSecurityConfiguration.class, SecurityStartupInvariant.class})
@ContextConfiguration(classes = GrainTradeApplication.class)
@ActiveProfiles("production")
class ProductionSecurityConfigurationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SecurityPrincipalRepository principals;

    @MockitoBean
    SecuritySessionAuditRecorder sessionAudit;

    @Autowired
    ClientRegistrationRepository clientRegistrations;

    @BeforeEach
    void authorizeKnownEnterpriseSubjects() {
        when(principals.findEnabled(any())).thenAnswer(invocation -> Optional.of(principal(
                invocation.getArgument(0),Set.of("BUSINESS_OPERATOR"))));
    }

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
    void oidcMfaSessionSubjectIsTheServletPrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").with(oidcLogin()
                        .idToken(token -> token.subject("oidc-subject")
                                .claim("amr", java.util.List.of("pwd", "mfa")))))
                .andExpect(status().isOk())
                .andExpect(content().string("oidc-subject"));
    }

    @Test
    void oidcSessionWithoutApprovedMfaEvidenceIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").with(oidcLogin()
                        .idToken(token -> token.subject("oidc-subject")
                                .claim("amr", java.util.List.of("pwd")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledAndRolelessOidcSubjectsAreRejectedOnEveryRequest() throws Exception {
        when(principals.findEnabled("disabled-subject")).thenReturn(Optional.empty());
        when(principals.findEnabled("roleless-subject"))
                .thenReturn(Optional.of(principal("roleless-subject",Set.of())));

        mockMvc.perform(get("/api/v1/whoami").with(oidcLogin()
                        .idToken(token -> token.subject("disabled-subject")
                                .claim("amr",List.of("mfa")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/whoami").with(oidcLogin()
                        .idToken(token -> token.subject("roleless-subject")
                                .claim("amr",List.of("mfa")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void browserLoginStartsAtTheControlledEnterpriseRegistration() throws Exception {
        mockMvc.perform(get("/api/v1/session/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/authorization/enterprise"));
    }

    @Test
    void logoutRequiresCsrfEndsTheServerSessionAndStartsProviderLogout() throws Exception {
        var login = oidcLogin()
                .clientRegistration(clientRegistrations.findByRegistrationId("enterprise"))
                .idToken(token -> token.subject("oidc-subject")
                .claim("amr", java.util.List.of("mfa")));
        mockMvc.perform(post("/api/v1/session/logout").with(login))
                .andExpect(status().isForbidden());
        clearInvocations(sessionAudit);
        mockMvc.perform(post("/api/v1/session/logout")
                        .param("_spring_security_internal_logout","true")
                        .with(login).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",org.hamcrest.Matchers.startsWith(
                        "https://issuer.example.test/logout")));
        verify(sessionAudit).record(eq("oidc-subject"),any(),eq("LOGOUT"),eq("{}"));
        verify(sessionAudit,never()).record(any(),any(),eq("OIDC_BACK_CHANNEL_LOGOUT"),any());
    }

    @Test
    void staleServerSessionIsRejectedAndAuditedWithoutLeakingTheCookie() throws Exception {
        mockMvc.perform(get("/api/v1/whoami").cookie(new Cookie("COFCO_SESSION","expired-session-id")))
                .andExpect(status().isUnauthorized());

        verify(sessionAudit).record(null,"expired-session-id","SESSION_EXPIRED","{}");
    }

    @Test
    void authenticatedProductionAsyncDispatchCompletesWithoutReauthorizationFailure() throws Exception {
        MvcResult initial = mockMvc.perform(get("/api/v1/whoami-async")
                        .with(oidcLogin().idToken(token -> token.subject("oidc-subject")
                                .claim("amr", java.util.List.of("mfa")))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string("oidc-subject"));
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

    private static SecurityPrincipal principal(String subjectId,Set<String> roles) {
        return new SecurityPrincipal(subjectId,subjectId,"TEST_UNIT","测试单位","ACTIVE","ACTIVE",
                roles,List.of(),Set.of("BUSINESS_READ"),Set.of("230202"));
    }
}
