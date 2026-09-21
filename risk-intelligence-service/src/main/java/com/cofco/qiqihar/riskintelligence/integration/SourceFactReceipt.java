package com.cofco.qiqihar.riskintelligence.integration;

import java.time.Instant;
import java.util.UUID;

public record SourceFactReceipt(
        UUID snapshotId,
        String payloadSha256,
        Instant ingestedAt,
        boolean created) { }
