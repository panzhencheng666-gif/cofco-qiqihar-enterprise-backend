package com.cofco.qiqihar.graintrade.shared.domain;

public record BusinessPageKey(String domain, String pageKind, String productCode) {

    public BusinessPageKey {
        requireText(domain, "domain");
        requireText(pageKind, "pageKind");
        if (productCode != null) {
            requireText(productCode, "productCode");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
