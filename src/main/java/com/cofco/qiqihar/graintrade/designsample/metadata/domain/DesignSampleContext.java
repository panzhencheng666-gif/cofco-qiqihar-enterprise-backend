package com.cofco.qiqihar.graintrade.designsample.metadata.domain;

import java.util.Objects;

public record DesignSampleContext(
        String domainCode,
        String productCode,
        String objectTypeCode) {
    public DesignSampleContext {
        domainCode = requireCode(domainCode, "domainCode");
        productCode = requireCode(productCode, "productCode");
        objectTypeCode = requireCode(objectTypeCode, "objectTypeCode");
    }

    private static String requireCode(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
