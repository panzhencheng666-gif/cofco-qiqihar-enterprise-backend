package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import java.util.Map;
import java.util.Objects;

public record ApiErrorResponse(ApiError error, String traceId) {

    public ApiErrorResponse {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public static ApiErrorResponse of(
            String code,
            String message,
            Map<String, Object> details,
            String traceId) {
        return new ApiErrorResponse(new ApiError(code, message, details), traceId);
    }

    public record ApiError(String code, String message, Map<String, Object> details) {

        public ApiError {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
        }
    }
}
