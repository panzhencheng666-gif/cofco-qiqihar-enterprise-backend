package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Objects;

public final class ServerContractException extends RuntimeException {
    private final String code;
    private final String clientMessage;

    public ServerContractException(String code, String clientMessage) {
        super(clientMessage);
        this.code = Objects.requireNonNull(code);
        this.clientMessage = Objects.requireNonNull(clientMessage);
    }

    public String code() { return code; }

    public String clientMessage() { return clientMessage; }
}
