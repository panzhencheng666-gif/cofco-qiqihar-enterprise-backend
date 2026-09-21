package com.cofco.qiqihar.riskintelligence.security;

public class RiskSessionValidationUnavailableException extends RuntimeException {
    public RiskSessionValidationUnavailableException(String message) {
        super(message);
    }

    public RiskSessionValidationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
