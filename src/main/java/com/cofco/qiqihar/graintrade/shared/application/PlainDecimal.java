package com.cofco.qiqihar.graintrade.shared.application;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Parses a bounded, non-exponent decimal before any potentially expensive BigDecimal operation. */
public final class PlainDecimal {
    private static final Pattern PLAIN = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    private PlainDecimal() {
    }

    public static BigDecimal parse(
            String value, int integerDigits, int fractionDigits, String code) {
        if (integerDigits < 1 || fractionDigits < 0) {
            throw new IllegalArgumentException("Decimal precision bounds are invalid");
        }
        int maximumLength = integerDigits + fractionDigits + (fractionDigits == 0 ? 1 : 2);
        if (value == null || value.isEmpty() || value.length() > maximumLength
                || !PLAIN.matcher(value).matches()) {
            throw invalid(code, "Decimal value is invalid");
        }
        int signOffset = value.charAt(0) == '-' ? 1 : 0;
        int decimalPoint = value.indexOf('.');
        int integerLength = (decimalPoint < 0 ? value.length() : decimalPoint) - signOffset;
        int fractionLength = decimalPoint < 0 ? 0 : value.length() - decimalPoint - 1;
        if (integerLength > integerDigits || fractionLength > fractionDigits) {
            throw invalid(code, "Decimal value is outside the allowed range");
        }
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.precision() - decimal.scale() > integerDigits || decimal.scale() > fractionDigits) {
            throw invalid(code, "Decimal value is outside the allowed range");
        }
        return decimal;
    }

    private static ClientRequestException invalid(String code, String message) {
        return new ClientRequestException(code, message);
    }
}
