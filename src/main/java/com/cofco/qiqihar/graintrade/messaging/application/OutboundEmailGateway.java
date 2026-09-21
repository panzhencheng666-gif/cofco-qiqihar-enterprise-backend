package com.cofco.qiqihar.graintrade.messaging.application;

public interface OutboundEmailGateway {
    void send(String email,String subject,String body);
}
