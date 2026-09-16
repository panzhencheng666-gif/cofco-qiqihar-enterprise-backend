package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** A session created only after a successful single-use email challenge. */
public final class EmailAuthenticationToken extends AbstractAuthenticationToken {
    private final String subject;
    private final long sessionVersion;
    public EmailAuthenticationToken(String subject,long sessionVersion) {
        super(List.of());this.subject=subject;this.sessionVersion=sessionVersion;super.setAuthenticated(true);
    }
    @Override public Object getCredentials(){return null;}
    @Override public Object getPrincipal(){return subject;}
    public long sessionVersion(){return sessionVersion;}
    @Override public void setAuthenticated(boolean authenticated) {
        if(authenticated)throw new IllegalArgumentException("Use a verified email identity");
        super.setAuthenticated(false);
    }
}
