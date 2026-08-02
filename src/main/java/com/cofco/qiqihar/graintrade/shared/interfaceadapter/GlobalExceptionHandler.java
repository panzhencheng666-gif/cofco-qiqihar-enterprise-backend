package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.WebUtils;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        return new ResponseEntity<>(null, headers, statusCode);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        String traceId = traceId(request);
        if (statusCode.is5xxServerError()) {
            LOGGER.error("Framework request failure [traceId={}]", traceId, exception);
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = status == null ? "HTTP_" + statusCode.value() : status.name();
        String message = status == null ? "Request failed" : status.getReasonPhrase();
        ApiErrorResponse response = ApiErrorResponse.of(
                code,
                message,
                Map.of(),
                traceId);
        ResponseEntity<Object> responseEntity =
                super.handleExceptionInternal(exception, response, headers, statusCode, request);
        if (responseEntity != null && HttpStatus.INTERNAL_SERVER_ERROR.equals(statusCode)) {
            request.setAttribute(
                    WebUtils.ERROR_EXCEPTION_ATTRIBUTE,
                    exception,
                    WebRequest.SCOPE_REQUEST);
        }
        return responseEntity;
    }

    @ExceptionHandler(ClientRequestException.class)
    ResponseEntity<ApiErrorResponse> handleClientRequest(
            ClientRequestException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                exception.code(),
                exception.clientMessage(),
                request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.NOT_FOUND,
                exception.code(),
                exception.clientMessage(),
                request);
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> handleAuthenticationRequired(
            AuthenticationRequiredException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        String traceId = traceId(request);
        LOGGER.error("Unhandled request failure [traceId={}]", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(
                        "INTERNAL_SERVER_ERROR",
                        "An unexpected error occurred",
                        Map.of(),
                        traceId));
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(code, message, Map.of(), traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        return traceId instanceof String value && !value.isBlank()
                ? value
                : UUID.randomUUID().toString();
    }

    private String traceId(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest
                ? traceId(servletWebRequest.getRequest())
                : UUID.randomUUID().toString();
    }
}
