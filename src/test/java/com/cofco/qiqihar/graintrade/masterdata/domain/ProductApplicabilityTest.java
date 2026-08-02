package com.cofco.qiqihar.graintrade.masterdata.domain;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductApplicabilityTest {

    @Test
    void reportsOnlyExplicitlyAssociatedProductsAsApplicable() {
        ProductApplicability applicability = new ProductApplicability(Set.of("RICE"));

        assertThat(applicability.supports("RICE")).isTrue();
        assertThat(applicability.supports("SOYBEAN")).isFalse();
    }
}
