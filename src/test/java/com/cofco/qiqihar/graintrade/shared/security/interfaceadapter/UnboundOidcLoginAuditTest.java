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
        assertEquals("/register.html",response.getRedirectedUrl());
    }
    @Test
    void newIdentityWithoutPasswordAuthenticationMustAuthenticateBeforeRegistration() throws Exception {
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
                .build();
        var user=new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),id);
        var auth=new OAuth2AuthenticationToken(user,user.getAuthorities(),"enterprise");
        var response=new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(),response,auth);
        verify(audit).record(eq("unbound-subject"),isNull(),eq("LOGIN_DENIED"),
                eq("{\"reason\":\"MFA_REQUIRED\"}"));
        assertEquals(302,response.getStatus());
        assertEquals("/oauth2/authorization/enterprise?reauthenticate=1",response.getRedirectedUrl());
    }
    @Test
    void reauthenticationForcesLoginWithoutChangingNormalSso() {
        var registration=org.springframework.security.oauth2.client.registration.ClientRegistration
                .withRegistrationId("enterprise").clientId("test-client")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.test/login/oauth2/code/enterprise")
                .authorizationUri("https://issuer.example.test/auth")
                .tokenUri("https://issuer.example.test/token").scope("openid").build();
        var resolver=new EnterpriseAuthorizationRequestResolver(
                new org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository(registration));
        var request=new MockHttpServletRequest();
        assertFalse(resolver.resolve(request,"enterprise").getAdditionalParameters().containsKey("prompt"));
        request.setParameter("reauthenticate","1");
        assertEquals("login",resolver.resolve(request,"enterprise").getAdditionalParameters().get("prompt"));
    }
    @Test
    void registrationStartsAtCreateFormAndRetainsExactCallbackAndState() {
        var registration=org.springframework.security.oauth2.client.registration.ClientRegistration
                .withRegistrationId("enterprise").clientId("test-client")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://localhost:29444/login/oauth2/code/enterprise")
                .authorizationUri("https://issuer.example.test/auth")
                .tokenUri("https://issuer.example.test/token").scope("openid").build();
        var resolver=new EnterpriseAuthorizationRequestResolver(
                new org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository(registration));
        var request=new MockHttpServletRequest();
        request.setParameter("register","1");
        var result=resolver.resolve(request,"enterprise");
        assertEquals("create",result.getAdditionalParameters().get("prompt"));
        assertEquals(registration.getRedirectUri(),result.getRedirectUri());
        assertNotNull(result.getState());
        assertTrue(result.getAuthorizationRequestUri().contains("prompt=create"));
        request.setParameter("reauthenticate","1");
        assertEquals("login",resolver.resolve(request,"enterprise").getAdditionalParameters().get("prompt"));
    }
}
