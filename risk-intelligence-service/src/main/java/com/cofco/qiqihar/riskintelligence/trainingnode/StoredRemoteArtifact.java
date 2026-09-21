package com.cofco.qiqihar.riskintelligence.trainingnode;

public record StoredRemoteArtifact(
        String artifactReference,
        String bundleSha256,
        String contentSha256,
        long sizeBytes) { }
