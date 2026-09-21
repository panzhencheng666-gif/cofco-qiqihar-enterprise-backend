package com.cofco.qiqihar.riskintelligence.security;

import org.springframework.http.HttpStatus;

public class RiskApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public RiskApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
