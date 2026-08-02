package com.cofco.qiqihar.graintrade.masterdata.domain;

import java.util.Objects;
import java.util.Set;

public record ProductApplicability(Set<String> productCodes) {

    public ProductApplicability {
        productCodes = Set.copyOf(Objects.requireNonNull(productCodes, "productCodes must not be null"));
    }

    public boolean supports(String productCode) {
        return productCodes.contains(Objects.requireNonNull(productCode, "productCode must not be null"));
    }
}
