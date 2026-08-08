package com.cofco.qiqihar.graintrade.shared.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlainDecimalTest {
    @Test
    void numericWithEqualPrecisionAndScaleAcceptsOnlyZeroIntegerPart() {
        assertThat(PlainDecimal.parse("0", 0, 4, "INVALID_DECIMAL").toPlainString()).isEqualTo("0");
        assertThat(PlainDecimal.parse("-0", 0, 4, "INVALID_DECIMAL").toPlainString()).isEqualTo("0");
        assertThat(PlainDecimal.parse("0.1234", 0, 4, "INVALID_DECIMAL").toPlainString()).isEqualTo("0.1234");
        assertThat(PlainDecimal.parse("-0.1234", 0, 4, "INVALID_DECIMAL").toPlainString()).isEqualTo("-0.1234");

        assertThatThrownBy(() -> PlainDecimal.parse("1.0000", 0, 4, "INVALID_DECIMAL"))
                .isInstanceOf(ClientRequestException.class)
                .hasMessageContaining("allowed range");
    }
}
