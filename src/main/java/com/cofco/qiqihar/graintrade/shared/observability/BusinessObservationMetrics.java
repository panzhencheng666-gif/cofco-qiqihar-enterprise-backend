package com.cofco.qiqihar.graintrade.shared.observability;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality business telemetry. Labels deliberately exclude identities,
 * business values, filenames, object locators, and request content.
 */
@Component
public class BusinessObservationMetrics {
    private final AtomicInteger activeImports = new AtomicInteger();
    private final AtomicLong eventBacklogPending = new AtomicLong();
    private final AtomicLong eventBacklogSeconds = new AtomicLong();
    private final AtomicInteger securitySecretKeyReady;
    private final Counter completedImports;
    private final Counter failedImports;
    private final Timer successfulReports;
    private final Timer failedReports;

    public BusinessObservationMetrics(MeterRegistry registry, DataSource dataSource,
            @Value("${qiqihar.security.oidc.client-id:}") String oidcClientId,
            @Value("${qiqihar.security.oidc.client-secret:}") String oidcClientSecret,
            @Value("${qiqihar.evidence.content.mode:database}") String contentMode,
            @Value("${qiqihar.evidence.content.oss.kms-key-reference:}") String kmsKeyReference,
            @Value("${qiqihar.evidence.content.oss.ram-role:}") String ramRole) {
        HikariDataSource hikari = dataSource instanceof HikariDataSource candidate ? candidate : null;
        boolean objectKeyReady = !"oss".equalsIgnoreCase(contentMode)
                || (present(kmsKeyReference) && present(ramRole));
        securitySecretKeyReady = new AtomicInteger(
                present(oidcClientId) && present(oidcClientSecret) && objectKeyReady ? 1 : 0);
        Gauge.builder("qiqihar.database.pool.active", this,
                        ignored -> poolValue(hikari, PoolValue.ACTIVE))
                .description("Active primary database connections")
                .register(registry);
        Gauge.builder("qiqihar.database.pool.idle", this,
                        ignored -> poolValue(hikari, PoolValue.IDLE))
                .description("Idle primary database connections")
                .register(registry);
        Gauge.builder("qiqihar.database.pool.maximum", this,
                        ignored -> poolValue(hikari, PoolValue.MAXIMUM))
                .description("Configured maximum primary database connections")
                .register(registry);
        Gauge.builder("qiqihar.database.pool.pending", this,
                        ignored -> poolValue(hikari, PoolValue.PENDING))
                .description("Threads waiting for a primary database connection")
                .register(registry);
        Gauge.builder("qiqihar.security.secret.key.ready", securitySecretKeyReady, AtomicInteger::get)
                .description("Required OIDC secret and external-content key references are configured")
                .register(registry);
        Gauge.builder("qiqihar.import.queue.active", activeImports, AtomicInteger::get)
                .description("Currently executing queued business imports")
                .register(registry);
        Gauge.builder("qiqihar.business.event.backlog.pending", eventBacklogPending, AtomicLong::get)
                .description("Last authorized durable business-event backlog count")
                .register(registry);
        Gauge.builder("qiqihar.business.event.backlog.seconds", eventBacklogSeconds, AtomicLong::get)
                .description("Age in seconds of the last authorized durable business-event backlog")
                .register(registry);
        completedImports = Counter.builder("qiqihar.import.jobs")
                .tag("outcome", "completed")
                .description("Queued business import outcomes")
                .register(registry);
        failedImports = Counter.builder("qiqihar.import.jobs")
                .tag("outcome", "failed")
                .description("Queued business import outcomes")
                .register(registry);
        successfulReports = Timer.builder("qiqihar.report.generation")
                .tag("outcome", "completed")
                .description("Formal report generation duration")
                .publishPercentileHistogram()
                .register(registry);
        failedReports = Timer.builder("qiqihar.report.generation")
                .tag("outcome", "failed")
                .description("Formal report generation duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void importStarted() {
        activeImports.incrementAndGet();
    }

    public void importFinished(boolean completed) {
        activeImports.updateAndGet(value -> Math.max(0, value - 1));
        (completed ? completedImports : failedImports).increment();
    }

    public Timer.Sample startReportGeneration() {
        return Timer.start();
    }

    public void reportFinished(Timer.Sample sample, boolean completed) {
        sample.stop(completed ? successfulReports : failedReports);
    }

    public void observeEventBacklog(long pendingCount, Instant oldestPendingAt, Instant observedAt) {
        eventBacklogPending.set(Math.max(0, pendingCount));
        long ageSeconds = oldestPendingAt == null
                ? 0
                : Math.max(0, observedAt.getEpochSecond() - oldestPendingAt.getEpochSecond());
        eventBacklogSeconds.set(ageSeconds);
    }

    private static double poolValue(HikariDataSource dataSource, PoolValue value) {
        if (dataSource == null || dataSource.getHikariPoolMXBean() == null) return 0;
        return switch (value) {
            case ACTIVE -> dataSource.getHikariPoolMXBean().getActiveConnections();
            case IDLE -> dataSource.getHikariPoolMXBean().getIdleConnections();
            case MAXIMUM -> dataSource.getMaximumPoolSize();
            case PENDING -> dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection();
        };
    }

    private enum PoolValue { ACTIVE, IDLE, MAXIMUM, PENDING }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
