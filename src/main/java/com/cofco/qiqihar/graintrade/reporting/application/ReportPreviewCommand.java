package com.cofco.qiqihar.graintrade.reporting.application;

public record ReportPreviewCommand(
        String definitionCode,
        String productCode,
        String cultivarCode,
        String regionLevel,
        String regionCode,
        String periodCode) {}
