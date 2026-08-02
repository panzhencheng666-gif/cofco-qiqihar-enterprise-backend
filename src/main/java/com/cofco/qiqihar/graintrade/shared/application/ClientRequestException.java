package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Objects;

/**
 * Signals a controlled client error whose code and message are safe to expose at a protocol boundary.
 */
public final class ClientRequestException extends RuntimeException {

    private final String code;
    private final String clientMessage;

    public ClientRequestException(String code, String clientMessage) {
        super(requireText(clientMessage, "clientMessage"));
        this.code = requireText(code, "code");
        this.clientMessage = clientMessage;
    }

    public String code() {
        return code;
    }

    public String clientMessage() {
        return clientMessage;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
