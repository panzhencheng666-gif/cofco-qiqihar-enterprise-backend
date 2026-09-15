package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Objects;
import java.util.Map;

/**
 * Signals a controlled client error whose code and message are safe to expose at a protocol boundary.
 */
public final class ClientRequestException extends RuntimeException {

    private final Map<String, Object> details;
    private final String code;
    private final String clientMessage;

    public ClientRequestException(String code, String clientMessage) {
        this(code, clientMessage, Map.of());
    }

    public ClientRequestException(String code, String clientMessage, Map<String, Object> details) {
        super(requireText(clientMessage, "clientMessage"));
        this.details = Map.copyOf(details);
        this.code = requireText(code, "code");
        this.clientMessage = clientMessage;
    }

    public Map<String, Object> details() { return details; }

    public static ClientRequestException field(String code, String field, String message) {
        return new ClientRequestException(code, message, Map.of("fieldErrors", Map.of(field, message)));
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
