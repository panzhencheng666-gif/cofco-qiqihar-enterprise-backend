package com.cofco.qiqihar.graintrade.identity.application;

public interface SmsVerificationGateway {
    void send(String phone, String purpose, String challengeId);
    boolean verify(String phone, String code, String challengeId);
}
