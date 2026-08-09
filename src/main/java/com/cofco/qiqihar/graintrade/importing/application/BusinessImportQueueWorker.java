package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.security.application.InternalSecuritySubjectScope;
import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
final class BusinessImportQueueWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessImportQueueWorker.class);
    private final ImportJobRepository jobs;
    private final SecurityPrincipalRepository principals;
    private final InternalSecuritySubjectScope subjects;
    private final Map<String, QueuedImportProcessor> processors;
    private final Clock clock;
    private final Duration staleAfter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity;
    private final Map<java.util.UUID, java.util.UUID> activeLeases = new ConcurrentHashMap<>();

    BusinessImportQueueWorker(ImportJobRepository jobs, SecurityPrincipalRepository principals,
            InternalSecuritySubjectScope subjects, List<QueuedImportProcessor> processors, Clock clock,
            @Value("${qiqihar.import.queue-stale-after:15m}") Duration staleAfter,
            @Value("${qiqihar.import.queue-concurrency:2}") int concurrency) {
        if (staleAfter.isNegative() || staleAfter.isZero() || concurrency < 1 || concurrency > 8) {
            throw new IllegalArgumentException("Import queue settings are invalid");
        }
        this.jobs = jobs;
        this.principals = principals;
        this.subjects = subjects;
        this.processors = processors.stream().collect(Collectors.toUnmodifiableMap(
                QueuedImportProcessor::domainCode, Function.identity()));
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.capacity = new Semaphore(concurrency);
    }

    @Scheduled(fixedDelayString = "${qiqihar.import.queue-poll-delay:1s}")
    void poll() {
        var now = clock.instant();
        activeLeases.forEach((jobId, leaseToken) -> {
            if (!jobs.heartbeat(jobId, leaseToken, now.plus(staleAfter))) {
                activeLeases.remove(jobId, leaseToken);
                LOGGER.debug("Import job lease is no longer active [jobId={}]", jobId);
            }
        });
        jobs.requeueExpired(now);
        while (capacity.tryAcquire()) {
            var claimed = jobs.claimNext(now, now.plus(staleAfter));
            if (claimed.isEmpty()) {
                capacity.release();
                return;
            }
            var stored = claimed.orElseThrow();
            activeLeases.put(stored.job().id(), stored.job().leaseToken());
            executor.submit(() -> process(stored));
        }
    }

    private void process(ImportJobRepository.StoredImportJob stored) {
        try {
            var principal = principals.findEnabled(stored.job().requestedBy())
                    .filter(value -> value.permits("BUSINESS_IMPORT"))
                    .orElseThrow(() -> new IllegalStateException("Queued import requester is no longer authorized"));
            QueuedImportProcessor processor = processors.get(stored.job().domainCode());
            if (processor == null) throw new IllegalStateException("Queued import domain is unsupported");
            subjects.callAs(principal.subjectId(), () -> {
                processor.processQueued(stored.job().id(), principal);
                return null;
            });
        } catch (RuntimeException exception) {
            Failure failure = failure(exception);
            LOGGER.warn("Queued import failed [jobId={}, code={}]", stored.job().id(), failure.code());
            try { jobs.fail(stored.job().id(), stored.job().leaseToken(),
                    failure.code(), failure.message(), clock.instant()); }
            catch (RuntimeException ignored) { /* completed or recovered by another owner */ }
        } finally {
            activeLeases.remove(stored.job().id(), stored.job().leaseToken());
            capacity.release();
        }
    }

    private static Failure failure(RuntimeException exception) {
        if (exception instanceof ClientRequestException client) {
            return new Failure(limit(client.code(), 100), limit(client.clientMessage(), 500));
        }
        if (exception instanceof ConflictException conflict) {
            return new Failure(limit(conflict.code(), 100), limit(conflict.clientMessage(), 500));
        }
        return new Failure("IMPORT_ASYNC_PROCESSING_FAILED",
                "The background import could not be completed; review the file and retry");
    }

    private static String limit(String value, int maximumCodePoints) {
        if (value == null || value.isBlank()) return "Import processing failed";
        int count = value.codePointCount(0, value.length());
        return count <= maximumCodePoints ? value
                : value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    @PreDestroy void close() { executor.close(); }

    private record Failure(String code, String message) {}
}
