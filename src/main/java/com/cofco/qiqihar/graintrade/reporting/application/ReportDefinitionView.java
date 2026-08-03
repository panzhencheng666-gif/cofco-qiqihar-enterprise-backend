package com.cofco.qiqihar.graintrade.reporting.application;

import java.util.List;

public record ReportDefinitionView(
        String code,
        String name,
        String businessDomain,
        String businessSubtype,
        String frequencyCode,
        int version,
        List<Section> sections) {
    public record Section(String code, String title, int sortOrder) {}
}
