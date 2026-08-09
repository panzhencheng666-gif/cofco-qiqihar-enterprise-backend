package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ImportJobRepository {
    ImportReservation reserve(String subjectId, String domainCode, String idempotencyKey,
            String digest, String workUnitCode, Instant now);
    ImportReservation queue(String subjectId, String domainCode, String idempotencyKey,
            String digest, String workUnitCode, UUID retryOf, String sourceContent, Instant now);
    Optional<StoredImportJob> findByIdempotency(String subjectId, String domainCode, String idempotencyKey);
    Optional<StoredImportJob> findById(UUID jobId);
    Optional<StoredImportJob> claimNext(Instant now, Instant leaseUntil);
    int requeueExpired(Instant now);
    boolean heartbeat(UUID jobId, UUID leaseToken, Instant leaseUntil);
    void fail(UUID jobId, UUID leaseToken, String failureCode, String failureMessage, Instant completedAt);
    ImportJob complete(ImportJob job, String sourceContent);

    record ImportReservation(boolean owner, StoredImportJob stored) {}
    record StoredImportJob(ImportJob job, String sourceContent) {}
}
