package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/** Stable same-origin entry into the enterprise OIDC authorization-code flow. */
@Controller
public class OidcLoginController {
    static final String LOGIN_RETURN_TO_ATTRIBUTE = "COFCO_LOGIN_RETURN_TO";
    private static final String RISK_APPLICATION_PATH = "/risk/";

    @GetMapping("/api/v1/session/login")
    RedirectView login(
            @RequestParam(required = false) String returnTo,
            HttpServletRequest request) {
        var session = request.getSession();
        session.removeAttribute(LOGIN_RETURN_TO_ATTRIBUTE);
        if (RISK_APPLICATION_PATH.equals(returnTo)) {
            session.setAttribute(LOGIN_RETURN_TO_ATTRIBUTE, RISK_APPLICATION_PATH);
        }
        RedirectView redirect = new RedirectView("/oauth2/authorization/enterprise");
        redirect.setExposeModelAttributes(false);
        return redirect;
    }
}
