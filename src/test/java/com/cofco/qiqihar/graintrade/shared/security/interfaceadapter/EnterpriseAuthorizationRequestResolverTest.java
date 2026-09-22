package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class EnterpriseAuthorizationRequestResolverTest {
    private final EnterpriseAuthorizationRequestResolver resolver = new EnterpriseAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("enterprise")
                    .clientId("enterprise").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationUri("https://idp.test/authorize").tokenUri("https://idp.test/token")
                    .redirectUri("https://app.test/login/oauth2/code/enterprise").scope("openid", "profile").build()));

    @Test
    void normalRiskEntryRetainsNoncePkceAndConfiguredCallbackWithoutLeakingLandingToProvider() {
        var request = request();
        request.setParameter("returnTo", "/risk/");
        var authorization = resolver.resolve(request);
        assertThat(authorization.getState()).isNotBlank();
        assertThat(authorization.getAttribute("nonce").toString()).isNotBlank();
        assertThat(authorization.getAdditionalParameters()).containsKeys("nonce", "code_challenge", "code_challenge_method");
        assertThat(authorization.getAttribute("code_verifier").toString()).isNotBlank();
        assertThat(authorization.getRedirectUri()).isEqualTo("https://app.test/login/oauth2/code/enterprise");
        assertThat(authorization.getAdditionalParameters()).doesNotContainKeys("returnTo", "prompt", "max_age");
        assertThat(authorization.getAuthorizationRequestUri()).doesNotContain("returnTo");
    }

    @Test
    void freshPasswordRequestKeepsOidcProofsAndRequestsFreshAuthentication() {
        var request = request();
        request.setParameter("reauthenticate", "1");
        var authorization = resolver.resolve(request, "enterprise");
        assertThat(authorization.getAdditionalParameters()).containsEntry("prompt", "login").containsEntry("max_age", 0)
                .containsKeys("nonce", "code_challenge");
        assertThat(authorization.getAttribute("code_verifier").toString()).isNotBlank();
        assertThat(authorization.getState()).isNotBlank();
    }

    @Test
    void registrationStillRequestsCreateWithoutForcingPasswordPrompt() {
        var request = request();
        request.setParameter("register", "1");
        var authorization = resolver.resolve(request);
        assertThat(authorization.getAdditionalParameters()).containsEntry("prompt", "create").doesNotContainKey("max_age");
        assertThat(authorization.getAdditionalParameters()).containsKeys("nonce", "code_challenge");
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/enterprise");
        request.setServletPath("/oauth2/authorization/enterprise");
        return request;
    }
}
