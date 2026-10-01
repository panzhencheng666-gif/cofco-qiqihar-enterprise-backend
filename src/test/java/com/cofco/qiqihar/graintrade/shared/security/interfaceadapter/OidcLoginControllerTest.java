package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OidcLoginControllerTest {
    @Test
    void recoveryConsumesFailureWithoutExposingProviderDetails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OidcLoginController()).build();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION,
                new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                        new org.springframework.security.oauth2.core.OAuth2Error("invalid_id_token"),
                        "private-provider-detail"));
        mvc.perform(get("/login").param("error", "").session(session))
                .andExpect(status().isOk())
                .andExpect(request().sessionAttributeDoesNotExist(
                        org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-provider-detail"))));
    }

    @Test
    void acceptsOnlyTheIndependentRiskApplicationAsLoginReturnTarget() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OidcLoginController()).build();

        mvc.perform(get("/api/v1/session/login").param("returnTo", "/risk/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/authorization/enterprise?returnTo=/risk/"))
                .andExpect(request().sessionAttributeDoesNotExist("COFCO_LOGIN_RETURN_TO"));

        mvc.perform(get("/api/v1/session/login").param("returnTo", "https://evil.example/risk/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/authorization/enterprise"))
                .andExpect(request().sessionAttributeDoesNotExist("COFCO_LOGIN_RETURN_TO"));
    }

    @Test
    void normalLoginClearsLegacyTargetAndDoesNotBorrowAnotherTabsTarget() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OidcLoginController()).build();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("COFCO_LOGIN_RETURN_TO", "/risk/");
        mvc.perform(get("/api/v1/session/login").session(session))
                .andExpect(header().string("Location", "/oauth2/authorization/enterprise"))
                .andExpect(request().sessionAttributeDoesNotExist("COFCO_LOGIN_RETURN_TO"));
        mvc.perform(get("/api/v1/session/login").param("returnTo", "/risk/", "https://evil.example"))
                .andExpect(header().string("Location", "/oauth2/authorization/enterprise"));
    }
}
