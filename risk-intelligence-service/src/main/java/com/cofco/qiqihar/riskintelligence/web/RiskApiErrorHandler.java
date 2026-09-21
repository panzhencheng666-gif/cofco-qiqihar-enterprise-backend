package com.cofco.qiqihar.riskintelligence.web;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RiskApiErrorHandler {
    @ExceptionHandler(RiskApiException.class)
    ResponseEntity<ErrorBody> riskError(RiskApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(ClientRequestException.class)
    ResponseEntity<ErrorBody> invalid(ClientRequestException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.code(), exception.clientMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ErrorBody> missing(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.code(), exception.clientMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorBody> conflict(ConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.code(), exception.clientMessage());
    }

    private static ResponseEntity<ErrorBody> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(
                code, message, Map.of(), UUID.randomUUID().toString()));
    }

    record ErrorBody(String code, String message, Map<String, Object> details, String traceId) { }
}
