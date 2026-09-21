package com.cofco.qiqihar.graintrade.identity.application;

public interface EmailVerificationGateway {
    void send(String email, String subject, String body, String verificationCode);
}
