package com.cofco.qiqihar.riskintelligence.trainingnode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RemoteTrainingJob(
        UUID executionId,
        UUID trainingRunId,
        UUID modelId,
        String modelCode,
        String modelKind,
        String domainCode,
        String baseModelReference,
        int modelVersion,
        UUID trainingSnapshotId,
        String dataSha256,
        long randomSeed,
        Instant leaseUntil,
        List<RemoteTrainingExample> examples) { }
