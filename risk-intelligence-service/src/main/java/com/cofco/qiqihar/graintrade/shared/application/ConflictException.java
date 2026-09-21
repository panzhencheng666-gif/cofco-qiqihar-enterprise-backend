package com.cofco.qiqihar.graintrade.shared.application;

public final class ConflictException extends RuntimeException {
    private final String code;
    private final String clientMessage;

    public ConflictException(String code, String clientMessage) {
        super(clientMessage);
        this.code = code;
        this.clientMessage = clientMessage;
    }

    public String code() { return code; }
    public String clientMessage() { return clientMessage; }
}
