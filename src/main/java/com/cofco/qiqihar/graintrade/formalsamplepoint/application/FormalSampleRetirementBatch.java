package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FormalSampleRetirementBatch(
        UUID id, String actorSubjectId, String workUnitCode, List<String> authorizedRegions,
        LocalDate businessDate, Instant expiresAt, List<Candidate> candidates,
        String reason, Integer retiredCount) {
    public int candidateCount() { return candidates.size(); }
    public record Candidate(UUID id, long version, String regionCode, String name) {}
}
