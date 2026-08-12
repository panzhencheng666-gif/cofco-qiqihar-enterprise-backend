package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/** Stable same-origin entry into the enterprise OIDC authorization-code flow. */
@Controller
public class OidcLoginController {

    @GetMapping("/api/v1/session/login")
    RedirectView login() {
        RedirectView redirect = new RedirectView("/oauth2/authorization/enterprise");
        redirect.setExposeModelAttributes(false);
        return redirect;
    }
}
