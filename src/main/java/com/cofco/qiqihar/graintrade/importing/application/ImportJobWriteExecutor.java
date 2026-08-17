package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits the job header before per-row transactions and closes the ledger atomically with its audit event. */
@Service
public class ImportJobWriteExecutor {
    private final ImportJobRepository jobs;
    private final BusinessAuditRecorder audit;

    public ImportJobWriteExecutor(ImportJobRepository jobs, BusinessAuditRecorder audit) {
        this.jobs = jobs;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportJobRepository.ImportReservation reserve(SecurityPrincipal principal, String domainCode,
            String key, String digest, Instant now) {
        return jobs.reserve(principal.subjectId(), domainCode, key, digest,
                principal.workUnitCode(), now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportJobRepository.ImportReservation queue(SecurityPrincipal principal, String domainCode,
            String key, String digest, UUID retryOf, String sourceContent, Instant now) {
        return jobs.queue(principal.subjectId(), domainCode, key, digest,
                principal.workUnitCode(), retryOf, sourceContent, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportJob complete(ImportJob job, String sourceContent, SecurityPrincipal principal) {
        ImportJob completed = jobs.complete(job, sourceContent);
        audit.record(principal, "IMPORT_JOB", completed.id().toString(), "IMPORT_JOB_COMPLETED",
                completed.completedAt(), "{\"importedRows\":" + completed.importedRows()
                        + ",\"failedRows\":" + completed.failedRows()
                        + ",\"warningRows\":" + completed.warningRows() + "}");
        return completed;
    }
}
