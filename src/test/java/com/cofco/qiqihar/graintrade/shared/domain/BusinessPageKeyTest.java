package com.cofco.qiqihar.graintrade.shared.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessPageKeyTest {

    @Test
    void preservesTheCanonicalBusinessPageIdentity() {
        BusinessPageKey key = new BusinessPageKey("MARKET", "QUALITY", "RICE");

        assertThat(key.domain()).isEqualTo("MARKET");
        assertThat(key.pageKind()).isEqualTo("QUALITY");
        assertThat(key.productCode()).isEqualTo("RICE");
    }

    @Test
    void rejectsBlankIdentityParts() {
        assertThatThrownBy(() -> new BusinessPageKey(" ", "QUALITY", "RICE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessPageKey("MARKET", "", "RICE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessPageKey("MARKET", "QUALITY", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
