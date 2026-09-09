package com.cofco.qiqihar.graintrade.shared.security.domain;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** A verified SMS session, never represented as an OIDC password or MFA assertion. */
public final class PhoneAuthenticationToken extends AbstractAuthenticationToken {
    private final String subject;
    private final long sessionVersion;
    public PhoneAuthenticationToken(String subject,long sessionVersion) {
        super(List.of());this.subject=subject;this.sessionVersion=sessionVersion;super.setAuthenticated(true);
    }
    @Override public Object getCredentials(){return null;}
    @Override public Object getPrincipal(){return subject;}
    public long sessionVersion(){return sessionVersion;}
    @Override public void setAuthenticated(boolean authenticated) {
        if(authenticated)throw new IllegalArgumentException("Use a verified SMS identity");
        super.setAuthenticated(false);
    }
}
