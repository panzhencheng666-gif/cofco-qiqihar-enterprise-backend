package com.cofco.qiqihar.graintrade.shared.application;

public final class ClientRequestException extends RuntimeException {
    private final String code;
    private final String clientMessage;

    public ClientRequestException(String code, String clientMessage) {
        super(clientMessage);
        this.code = code;
        this.clientMessage = clientMessage;
    }

    public String code() { return code; }
    public String clientMessage() { return clientMessage; }
}
