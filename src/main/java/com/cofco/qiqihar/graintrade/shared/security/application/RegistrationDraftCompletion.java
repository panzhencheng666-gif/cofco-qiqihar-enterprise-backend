package com.cofco.qiqihar.graintrade.shared.security.application;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Completes a verified registration draft after enterprise authentication. */
public interface RegistrationDraftCompletion {
    boolean complete(HttpSession session, OidcUser user);
    void failed(HttpSession session, String message);
}
