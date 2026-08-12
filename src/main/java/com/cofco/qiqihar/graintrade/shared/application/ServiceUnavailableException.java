package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Objects;

public class ServiceUnavailableException extends RuntimeException {
    private final String code;
    private final String clientMessage;

    public ServiceUnavailableException(String code, String clientMessage) {
        super(clientMessage);
        this.code = Objects.requireNonNull(code);
        this.clientMessage = Objects.requireNonNull(clientMessage);
    }

    public ServiceUnavailableException(String code, String clientMessage, Throwable cause) {
        super(clientMessage, cause);
        this.code = Objects.requireNonNull(code);
        this.clientMessage = Objects.requireNonNull(clientMessage);
    }

    public String code() { return code; }

    public String clientMessage() { return clientMessage; }
}
