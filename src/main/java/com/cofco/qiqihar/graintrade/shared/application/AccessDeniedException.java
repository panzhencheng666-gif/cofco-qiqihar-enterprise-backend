package com.cofco.qiqihar.graintrade.shared.application;

public final class AccessDeniedException extends RuntimeException {
    private final String code;

    public AccessDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
