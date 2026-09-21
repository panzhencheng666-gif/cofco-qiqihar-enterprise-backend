package com.cofco.qiqihar.riskintelligence.integration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

record SourceFactSnapshot(
        UUID snapshotId,
        SourceFactKey key,
        Instant businessOccurredAt,
        Instant ingestedAt,
        String payloadSha256,
        Map<String, Object> payload) { }
