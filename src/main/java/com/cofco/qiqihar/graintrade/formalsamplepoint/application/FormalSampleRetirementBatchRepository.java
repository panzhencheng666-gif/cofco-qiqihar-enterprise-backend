package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import java.util.Optional;
import java.util.UUID;

public interface FormalSampleRetirementBatchRepository {
    void save(FormalSampleRetirementBatch batch);
    Optional<FormalSampleRetirementBatch> find(UUID id, boolean lock);
    void complete(UUID id, String reason, int count);
}
