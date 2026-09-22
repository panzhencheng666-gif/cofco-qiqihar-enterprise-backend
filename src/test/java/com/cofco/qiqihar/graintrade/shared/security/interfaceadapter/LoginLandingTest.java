package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.*;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginLandingTest {
    private final SecurityPrincipal reporter = new SecurityPrincipal("reporter", "Reporter", "TEST", "Test", "ACTIVE", "ACTIVE",
            Set.of("BUSINESS_OPERATOR"), List.of(), Set.of("BUSINESS_UPDATE", "BUSINESS_SUBMIT"), Set.of());

    @Test void apiSavedRequestLandsOnWorkbenchInsteadOfJson() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/session/me");
        request.setServletPath("/api/v1/session/me");
        var cache = new HttpSessionRequestCache();
        cache.saveRequest(request, new MockHttpServletResponse());
        var response = login(request);
        assertEquals("/workbench/", response.getRedirectedUrl());
        assertNull(cache.getRequest(request, response));
    }

    @Test void normalSavedBusinessPageIsRetained() throws Exception {
        var request = new MockHttpServletRequest("GET", "/workbench/");
        request.setServletPath("/workbench/");
        new HttpSessionRequestCache().saveRequest(request, new MockHttpServletResponse());
        assertTrue(login(request).getRedirectedUrl().startsWith("http://localhost/workbench/"));
    }

    @Test void freshReporterLoginLandsOnWorkbench() throws Exception {
        assertEquals("/workbench/", login(new MockHttpServletRequest()).getRedirectedUrl());
    }

    @Test void freshAdministratorLoginKeepsTheApplicationCenterLanding() throws Exception {
        var admin = new SecurityPrincipal("admin", "Administrator", "TEST", "Test", "ACTIVE", "ACTIVE",
                Set.of("SYSTEM_ADMIN"), List.of(), Set.of(), Set.of());
        assertEquals("/", login(new MockHttpServletRequest(), admin).getRedirectedUrl());
    }

    @Test void requestedRiskLoginReturnsToIndependentRiskApplication() throws Exception {
        var request = new MockHttpServletRequest();
        request.setAttribute("COFCO_OIDC_CALLBACK_RETURN_TO", "/risk/");
        assertEquals("/risk/", login(request).getRedirectedUrl());
        assertNull(request.getSession().getAttribute("COFCO_LOGIN_RETURN_TO"));
    }

    @Test void legacySessionTargetCannotOverrideANormalCallback() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("COFCO_LOGIN_RETURN_TO", "/risk/");
        assertEquals("/workbench/", login(request).getRedirectedUrl());
        assertNull(request.getSession().getAttribute("COFCO_LOGIN_RETURN_TO"));
    }

    @Test void arbitraryCallbackTargetCannotRedirectOutsideTheApplication() throws Exception {
        var request = new MockHttpServletRequest();
        request.setAttribute("COFCO_OIDC_CALLBACK_RETURN_TO", "https://evil.example");
        assertEquals("/workbench/", login(request).getRedirectedUrl());
    }

    @Test void browserDocumentRedirectsButApiClientsKeepJson() throws Exception {
        var access = mock(AccessControl.class);
        when(access.requireAuthenticated()).thenReturn(reporter);
        var mvc = MockMvcBuilders.standaloneSetup(new SessionController(access)).build();
        mvc.perform(get("/api/v1/session/me").header("Sec-Fetch-Dest", "document"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/workbench/"));
        mvc.perform(get("/api/v1/session/me").header("Accept", "application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.subjectId").value("reporter"))
                .andExpect(jsonPath("$.data.unassignedReporter").value(true));
    }

    private MockHttpServletResponse login(MockHttpServletRequest request) throws Exception {
        return login(request, reporter);
    }

    private MockHttpServletResponse login(MockHttpServletRequest request, SecurityPrincipal principal) throws Exception {
        var principals = mock(SecurityPrincipalRepository.class);
        when(principals.findEnabledByOidcIdentity("https://issuer.example.test", "oidc-reporter")).thenReturn(Optional.of(principal));
        var type = Class.forName(ProductionSecurityConfiguration.class.getName()+"$EnterpriseAuthenticationSuccessHandler");
        var constructor = type.getDeclaredConstructor(SecurityPrincipalRepository.class, SecuritySessionAuditRecorder.class,
                Set.class, Set.class, RegistrationDraftCompletion.class);
        constructor.setAccessible(true);
        var handler = (AuthenticationSuccessHandler)constructor.newInstance(principals, mock(SecuritySessionAuditRecorder.class),
                Set.of("pwd"), Set.of(), mock(RegistrationDraftCompletion.class));
        var now = Instant.now();
        var token = OidcIdToken.withTokenValue("test-only").issuer("https://issuer.example.test").subject("oidc-reporter")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("amr", List.of("pwd")).build();
        var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token);
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request,response,new OAuth2AuthenticationToken(user,user.getAuthorities(),"enterprise"));
        return response;
    }
}
