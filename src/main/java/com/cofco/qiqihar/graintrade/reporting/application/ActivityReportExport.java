package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;

public record ActivityReportExport(
        String id, String filename, String contentType, String sha256, Instant generatedAt) {
    public record Content(String filename, String contentType, byte[] bytes) {}
}
