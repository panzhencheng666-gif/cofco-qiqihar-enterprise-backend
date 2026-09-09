package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Require a fresh password authentication after identity-provider registration. */
final class EnterpriseAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    EnterpriseAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null || !"1".equals(request.getParameter("reauthenticate"))) return authorization;
        var parameters = new HashMap<>(authorization.getAdditionalParameters());
        parameters.put("prompt", "login");
        // OIDC requires auth_time in the ID token when max_age is requested.
        parameters.put("max_age", 0);
        return OAuth2AuthorizationRequest.from(authorization).additionalParameters(parameters).build();
    }
}
