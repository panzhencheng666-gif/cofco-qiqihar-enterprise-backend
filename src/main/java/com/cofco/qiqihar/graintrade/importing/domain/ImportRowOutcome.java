package com.cofco.qiqihar.graintrade.importing.domain;

import java.util.Map;

public record ImportRowOutcome(
        int rowNumber,
        String outcomeCode,
        String errorCode,
        String errorMessage,
        String businessRecordId,
        Map<String, String> values) {
    public ImportRowOutcome {
        values = Map.copyOf(values);
    }

    public static ImportRowOutcome imported(int rowNumber, String businessRecordId, Map<String, String> values) {
        return new ImportRowOutcome(rowNumber, "IMPORTED", null, null, businessRecordId, values);
    }

    public static ImportRowOutcome error(int rowNumber, String code, String message, Map<String, String> values) {
        return new ImportRowOutcome(rowNumber, "ERROR", code, message, null, values);
    }
}
