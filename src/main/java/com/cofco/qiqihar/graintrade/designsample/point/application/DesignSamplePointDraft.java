package com.cofco.qiqihar.graintrade.designsample.point.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record DesignSamplePointDraft(
        String contractVersion,
        String contractDigest,
        DesignSampleContext context,
        Map<String, JsonNode> values) {
    public DesignSamplePointDraft {
        values = values == null ? null : Map.copyOf(values);
    }
}
