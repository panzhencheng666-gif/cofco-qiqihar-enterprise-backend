package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.SecuritySessionAuditRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UnboundOidcLoginAuditTest {
    @Test
    void passwordLoginAwaitingBindingUsesExistingAuditContractAndRedirects() throws Exception {
        var principals=mock(SecurityPrincipalRepository.class);
        var audit=mock(SecuritySessionAuditRecorder.class);
        var type=Class.forName(ProductionSecurityConfiguration.class.getName()+"$EnterpriseAuthenticationSuccessHandler");
        var constructor=type.getDeclaredConstructor(SecurityPrincipalRepository.class,
                SecuritySessionAuditRecorder.class,Set.class,Set.class);
        constructor.setAccessible(true);
        var handler=(AuthenticationSuccessHandler)constructor.newInstance(principals,audit,Set.of("pwd"),Set.of());
        var now=Instant.now();
        var id=OidcIdToken.withTokenValue("test-only").issuer("https://issuer.example.test")
                .subject("unbound-subject").issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("amr",List.of("pwd")).build();
        var user=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),id);
        var auth=new OAuth2AuthenticationToken(user,user.getAuthorities(),"enterprise");
        var response=new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(),response,auth);
        verify(audit).record(eq("unbound-subject"),anyString(),eq("LOGIN_SUCCESS"),
                eq("{\"activationRequired\":true}"));
        assertEquals(302,response.getStatus());
        assertEquals("/",response.getRedirectedUrl());
    }
}
