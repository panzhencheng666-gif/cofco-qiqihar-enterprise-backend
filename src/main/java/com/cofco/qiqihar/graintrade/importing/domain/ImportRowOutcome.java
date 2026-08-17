package com.cofco.qiqihar.graintrade.importing.domain;

import java.util.Map;
import java.util.UUID;

public record ImportRowOutcome(
        int rowNumber,
        String outcomeCode,
        String errorCode,
        String errorMessage,
        String businessRecordId,
        String importDraftId,
        String warningCode,
        String warningMessage,
        Map<String, String> values) {
    public ImportRowOutcome {
        values = Map.copyOf(values);
    }

    public static ImportRowOutcome imported(int rowNumber, String businessRecordId, Map<String, String> values) {
        return new ImportRowOutcome(rowNumber, "IMPORTED", null, null,
                businessRecordId, null, null, null, values);
    }

    public static ImportRowOutcome draftImported(int rowNumber, UUID importDraftId,
            String warningCode, String warningMessage, Map<String, String> values) {
        return new ImportRowOutcome(rowNumber, "IMPORTED", null, null, null,
                importDraftId.toString(), warningCode, warningMessage, values);
    }

    public static ImportRowOutcome error(int rowNumber, String code, String message, Map<String, String> values) {
        return new ImportRowOutcome(rowNumber, "ERROR", code, message,
                null, null, null, null, values);
    }
}
