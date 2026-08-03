package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;
import java.util.List;

public record ReportPreviewView(
        String id,
        String definitionCode,
        String datasetId,
        String title,
        String dataCutoffLabel,
        List<Line> lines,
        List<Section> sections,
        Instant expiresAt,
        long version,
        boolean legacyReadOnly) {
    public record Line(String label, String value, String note) {}
    public record Section(String code, String title, String body) {}
}
