package com.cofco.qiqihar.graintrade.shared.application;

/** Raised by an application port when a state-changing request has no authenticated principal. */
public final class AuthenticationRequiredException extends RuntimeException {
    public AuthenticationRequiredException() {
        super("Authentication is required");
    }
}
