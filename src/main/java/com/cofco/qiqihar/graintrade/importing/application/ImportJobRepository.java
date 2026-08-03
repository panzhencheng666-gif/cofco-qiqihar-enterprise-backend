package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import java.util.Optional;
import java.util.UUID;

public interface ImportJobRepository {
    Optional<StoredImportJob> findByIdempotency(String subjectId, String domainCode, String idempotencyKey);
    Optional<StoredImportJob> findById(UUID jobId);
    ImportJob save(ImportJob job, String sourceContent);

    record StoredImportJob(ImportJob job, String sourceContent) {}
}
