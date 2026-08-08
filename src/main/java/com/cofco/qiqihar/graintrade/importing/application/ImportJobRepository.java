package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ImportJobRepository {
    ImportReservation reserve(String subjectId, String domainCode, String idempotencyKey,
            String digest, String workUnitCode, Instant now);
    Optional<StoredImportJob> findByIdempotency(String subjectId, String domainCode, String idempotencyKey);
    Optional<StoredImportJob> findById(UUID jobId);
    ImportJob complete(ImportJob job, String sourceContent);

    record ImportReservation(boolean owner, StoredImportJob stored) {}
    record StoredImportJob(ImportJob job, String sourceContent) {}
}
