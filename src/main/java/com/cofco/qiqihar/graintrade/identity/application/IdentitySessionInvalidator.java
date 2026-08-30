package com.cofco.qiqihar.graintrade.identity.application;

/** Invalidates every shared browser/OIDC session after an identity or authorization change. */
public interface IdentitySessionInvalidator {
    void invalidate(String subjectId,String reason);
}
