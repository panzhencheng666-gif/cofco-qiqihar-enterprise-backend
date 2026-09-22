package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OidcLoginControllerTest {
    @Test
    void acceptsOnlyTheIndependentRiskApplicationAsLoginReturnTarget() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new OidcLoginController()).build();

        mvc.perform(get("/api/v1/session/login").param("returnTo", "/risk/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/authorization/enterprise"))
                .andExpect(request().sessionAttribute("COFCO_LOGIN_RETURN_TO", "/risk/"));

        mvc.perform(get("/api/v1/session/login").param("returnTo", "https://evil.example/risk/"))
                .andExpect(status().isFound())
                .andExpect(request().sessionAttributeDoesNotExist("COFCO_LOGIN_RETURN_TO"));
    }
}
