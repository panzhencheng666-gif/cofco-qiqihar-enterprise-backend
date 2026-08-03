package com.cofco.qiqihar.graintrade.reporting.application;

import java.time.Instant;

public record ReportPublicationView(String id, String previewId, String exportTaskId, Instant publishedAt, long version) {}
