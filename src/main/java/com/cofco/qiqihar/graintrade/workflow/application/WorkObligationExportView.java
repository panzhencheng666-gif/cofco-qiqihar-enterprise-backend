package com.cofco.qiqihar.graintrade.workflow.application;

import java.time.Instant;

public record WorkObligationExportView(
        String id,
        String filename,
        String contentType,
        String checksum,
        Instant generatedAt) {}
