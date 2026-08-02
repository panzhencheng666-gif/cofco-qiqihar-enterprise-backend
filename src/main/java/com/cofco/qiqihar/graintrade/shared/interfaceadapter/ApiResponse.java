package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import java.util.Objects;

public record ApiResponse<T>(T data) {

    public ApiResponse {
        Objects.requireNonNull(data, "data must not be null");
    }
}
