package com.cofco.qiqihar.graintrade.shared.application;

import java.util.List;

public record PagedResult<T>(List<T> items, int pageNumber, int pageSize, long totalElements) {

    public PagedResult {
        items = List.copyOf(items);
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must not be negative");
        }
    }

    public int totalPages() {
        return (int) ((totalElements + pageSize - 1) / pageSize);
    }
}
