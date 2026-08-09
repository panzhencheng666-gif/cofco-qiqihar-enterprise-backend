package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.Locale;

record ImportMenuContext(String productCode, String objectTypeCode) {
    ImportMenuContext {
        productCode = required(productCode);
        objectTypeCode = required(objectTypeCode);
    }

    void requireMatches(String actualProductCode, String actualObjectTypeCode) {
        if (!productCode.equals(normalized(actualProductCode))
                || !objectTypeCode.equals(normalized(actualObjectTypeCode))) {
            throw new ClientRequestException(
                    "IMPORT_CONTEXT_MISMATCH",
                    "Import workbook does not belong to the current menu context");
        }
    }

    private static String required(String value) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new ClientRequestException("INVALID_IMPORT_REQUEST", "Import menu context is required");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
