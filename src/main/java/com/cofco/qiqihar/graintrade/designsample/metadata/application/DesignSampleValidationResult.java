package com.cofco.qiqihar.graintrade.designsample.metadata.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import java.util.LinkedHashMap;
import java.util.Map;

public record DesignSampleValidationResult(
        String contractVersion,
        String contractDigest,
        DesignSampleContext context,
        Map<String, ValueState> valueStates) {
    public DesignSampleValidationResult {
        valueStates = Map.copyOf(new LinkedHashMap<>(valueStates));
    }

    public enum ValueState {
        UNKNOWN,
        KNOWN
    }
}
