package com.cofco.qiqihar.graintrade.designsample.metadata.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleValidationResult.ValueState;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record ValidatedDesignSampleValues(
        String contractVersion,
        String contractDigest,
        DesignSampleContext context,
        Map<String, JsonNode> values,
        Map<String, ValueState> valueStates) {
    public ValidatedDesignSampleValues {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        valueStates = Collections.unmodifiableMap(new LinkedHashMap<>(valueStates));
    }

    DesignSampleValidationResult publicResult() {
        return new DesignSampleValidationResult(
                contractVersion, contractDigest, context, valueStates);
    }
}
