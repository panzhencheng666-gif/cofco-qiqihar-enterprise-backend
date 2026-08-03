package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;

public record ReportExportView(
        String id, String previewId, String formatCode, String filename, String contentType, Instant requestedAt) {}
