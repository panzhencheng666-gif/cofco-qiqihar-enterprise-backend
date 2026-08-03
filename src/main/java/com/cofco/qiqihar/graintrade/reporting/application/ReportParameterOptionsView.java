package com.cofco.qiqihar.graintrade.reporting.application;

import java.util.List;

public record ReportParameterOptionsView(
        List<ReportDefinitionView> definitions,
        List<Option> products,
        List<Option> cultivars,
        List<Option> regionLevels,
        List<Option> regions,
        List<Option> periods,
        List<Option> formats) {
    public record Option(String code, String label) {}
}
