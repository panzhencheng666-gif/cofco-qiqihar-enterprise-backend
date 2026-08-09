package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobRepository;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.infrastructure.JdbcImportJobRepository;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditWriter;
import com.cofco.qiqihar.graintrade.shared.audit.domain.BusinessAuditEvent;
import com.cofco.qiqihar.graintrade.shared.audit.infrastructure.JdbcBusinessAuditWriter;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = GrainTradeApplication.class,
        properties = "qiqihar.import.reservation-lock-timeout=500ms")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Import(ProductionImportConcurrencyIntegrationTest.ConcurrencyConfiguration.class)
class ProductionImportConcurrencyIntegrationTest {
    private static final String PHOTO_ID = "00000000-0000-0000-0000-000000000031";
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired CoordinatedImportJobRepository importJobs;
    @Autowired FaultInjectingBusinessAuditWriter auditWriter;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        importJobs.reset();
        auditWriter.reset();
        truncateImportEffects();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(CAST(:id AS uuid),'STAGED','fixture.png','image/png',decode('00','hex'),decode('01','hex'),
                  1,repeat('c',64),now(),47.3543,123.9182,'测试水印','production-tester',now())
                """).param("id", PHOTO_ID).update();
    }

    @AfterEach
    void cleanAfterEach() {
        truncateImportEffects();
    }

    @Test
    void sameUnusedKeyFromTwoThreadsHasExactlyOneDurableEffect() throws Exception {
        byte[] csv = (String.join(",", ProductionImportTemplate.HEADERS) + "\n"
                + row(Map.of()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        List<MvcResult> results = submitConcurrently("atomic-concurrent-import", csv, csv);

        assertThat(count("platform.import_job")).isEqualTo(1);
        assertThat(count("platform.import_row_result")).isEqualTo(1);
        assertThat(count("production.production_record")).isEqualTo(1);
        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
        assertThat(jobId(results.get(0))).isEqualTo(jobId(results.get(1)));
        assertThat(importJobs.lockTimeoutWasRestored()).isTrue();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE action_code IN ('PRODUCTION_RECORD_CREATED', 'IMPORT_JOB_COMPLETED')
                """).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void sameUnusedKeyWithDifferentDigestsHasOneOwnerAndOneConflict() throws Exception {
        byte[] firstCsv = csv(Map.of("cultivatedAreaMu", "10.5"));
        byte[] secondCsv = csv(Map.of("cultivatedAreaMu", "11.5"));

        List<MvcResult> results = submitConcurrently("atomic-digest-conflict", firstCsv, secondCsv);

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
                .singleElement().satisfies(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("IMPORT_IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(count("platform.import_job")).isEqualTo(1);
        assertThat(count("platform.import_row_result")).isEqualTo(1);
        assertThat(count("production.production_record")).isEqualTo(1);
    }

    @Test
    void completionFailureRollsBackEveryImportEffect() throws Exception {
        importJobs.failAfterNextCompletion();

        MvcResult result = submit("completion-failure", csv(Map.of()));

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertNoDurableEffects();
    }

    @Test
    void auditFailureRollsBackEveryImportEffect() throws Exception {
        auditWriter.failNextImportCompletion();

        MvcResult result = submit("audit-failure", csv(Map.of()));

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertNoDurableEffects();
    }

    @Test
    void reservationLockWaitIsBoundedAndReturnsRetryableConflict() throws Exception {
        try (Connection owner = dataSource.getConnection();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            owner.setAutoCommit(false);
            insertUncommittedReservation(owner, "held-reservation");

            Future<MvcResult> request = executor.submit(() -> submit("held-reservation", csv(Map.of())));
            MvcResult result;
            try {
                result = request.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                owner.rollback();
                request.get(5, TimeUnit.SECONDS);
                throw new AssertionError("Reservation lock wait exceeded the test's three-second outer bound", exception);
            }

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString()).contains("IMPORT_RESERVATION_BUSY");
            assertNoDurableEffects();
            owner.rollback();
        }
        assertNoDurableEffects();
    }

    private List<MvcResult> submitConcurrently(String key, byte[] firstCsv, byte[] secondCsv) throws Exception {
        importJobs.coordinateNextTwoReservations();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> submit(key, firstCsv));
            Future<MvcResult> second = executor.submit(() -> submit(key, secondCsv));
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private MvcResult submit(String key, byte[] csv) throws Exception {
        return mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "production.csv", "text/csv", csv))
                        .param("productCode", "CORN")
                        .param("objectTypeCode", "FARMER")
                        .header("Idempotency-Key", key)
                        .principal(() -> "production-tester"))
                .andReturn();
    }

    private static String jobId(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private static String row(Map<String, String> overrides) {
        Map<String, String> values = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("productCode", "CORN"), Map.entry("objectTypeCode", "FARMER"),
                Map.entry("regionCode", "230200"), Map.entry("cultivarCode", ""),
                Map.entry("surveyDate", "2026-07-31"), Map.entry("cultivatedAreaMu", "10.5"),
                Map.entry("yieldPerMuKilograms", "20"), Map.entry("PROD_REPORTER_NAME", "\u5e76\u53d1\u5bfc\u5165\u5458"),
                Map.entry("PROD_REPORTER_PHONE", "13800000000"), Map.entry("PROD_SAMPLE_CONTACT", "13900000000"),
                Map.entry("PROD_SAMPLE_LATITUDE", "47.3543"), Map.entry("PROD_SAMPLE_LONGITUDE", "123.9182"),
                Map.entry("evidencePhotoId", PHOTO_ID)));
        values.putAll(overrides);
        return ProductionImportTemplate.HEADERS.stream().map(values::get).collect(java.util.stream.Collectors.joining(","));
    }

    private static byte[] csv(Map<String, String> overrides) {
        return (String.join(",", ProductionImportTemplate.HEADERS) + "\n" + row(overrides) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void insertUncommittedReservation(Connection connection, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO platform.import_job(import_job_id,domain_code,idempotency_key,content_sha256,source_content,
                  requested_by,work_unit_code,retry_of_import_job_id,status_code,created_at,completed_at)
                VALUES(?,?,?,?,?,?,?,NULL,'COMPLETED',?,?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, ProductionImportTemplate.DOMAIN);
            statement.setString(3, key);
            statement.setString(4, "0".repeat(64));
            statement.setString(5, "held by test transaction");
            statement.setString(6, "production-tester");
            statement.setString(7, "TEST");
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setTimestamp(9, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void assertNoDurableEffects() {
        assertThat(count("platform.import_job")).isZero();
        assertThat(count("platform.import_row_result")).isZero();
        assertThat(count("production.production_record")).isZero();
        assertThat(count("platform.business_audit_event")).isZero();
    }

    private void truncateImportEffects() {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record,evidence.evidence_photo RESTART IDENTITY CASCADE
                """).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyConfiguration {
        @Bean
        CoordinatedImportJobRepository coordinatedImportJobRepository(
                JdbcImportJobRepository delegate, JdbcClient jdbc) {
            return new CoordinatedImportJobRepository(delegate, jdbc);
        }

        @Bean
        @Primary
        ImportJobRepository testImportJobRepository(CoordinatedImportJobRepository repository) {
            return repository;
        }

        @Bean
        FaultInjectingBusinessAuditWriter faultInjectingBusinessAuditWriter(JdbcBusinessAuditWriter delegate) {
            return new FaultInjectingBusinessAuditWriter(delegate);
        }

        @Bean
        @Primary
        BusinessAuditWriter testBusinessAuditWriter(FaultInjectingBusinessAuditWriter writer) {
            return writer;
        }
    }

    static final class CoordinatedImportJobRepository implements ImportJobRepository {
        private final ImportJobRepository delegate;
        private final JdbcClient jdbc;
        private volatile CountDownLatch reservationBoundary;
        private volatile boolean failAfterCompletion;
        private volatile boolean lockTimeoutRestored = true;

        private CoordinatedImportJobRepository(ImportJobRepository delegate, JdbcClient jdbc) {
            this.delegate = delegate;
            this.jdbc = jdbc;
        }

        @Override
        public ImportReservation reserve(String subjectId, String domainCode, String idempotencyKey,
                String digest, String workUnitCode, Instant now) {
            CountDownLatch boundary = reservationBoundary;
            if (boundary != null) {
                boundary.countDown();
                try {
                    if (!boundary.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Both requests did not reach the reservation boundary");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Concurrent reservation test was interrupted", exception);
                }
            }
            String before = currentLockTimeout();
            ImportReservation reservation = delegate.reserve(
                    subjectId, domainCode, idempotencyKey, digest, workUnitCode, now);
            if (!before.equals(currentLockTimeout())) lockTimeoutRestored = false;
            return reservation;
        }

        @Override
        public Optional<StoredImportJob> findByIdempotency(String subjectId, String domainCode, String idempotencyKey) {
            return delegate.findByIdempotency(subjectId, domainCode, idempotencyKey);
        }

        @Override
        public Optional<StoredImportJob> findById(UUID jobId) {
            return delegate.findById(jobId);
        }

        @Override
        public ImportJob complete(ImportJob job, String sourceContent) {
            ImportJob completed = delegate.complete(job, sourceContent);
            if (failAfterCompletion) {
                failAfterCompletion = false;
                throw new IllegalStateException("Injected import completion failure");
            }
            return completed;
        }

        void coordinateNextTwoReservations() {
            reservationBoundary = new CountDownLatch(2);
        }

        void failAfterNextCompletion() {
            failAfterCompletion = true;
        }

        void reset() {
            reservationBoundary = null;
            failAfterCompletion = false;
            lockTimeoutRestored = true;
        }

        boolean lockTimeoutWasRestored() {
            return lockTimeoutRestored;
        }

        private String currentLockTimeout() {
            return jdbc.sql("SELECT current_setting('lock_timeout')").query(String.class).single();
        }
    }

    static final class FaultInjectingBusinessAuditWriter implements BusinessAuditWriter {
        private final BusinessAuditWriter delegate;
        private volatile boolean failImportCompletion;

        private FaultInjectingBusinessAuditWriter(BusinessAuditWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(BusinessAuditEvent event) {
            if (failImportCompletion && event.actionCode().equals("IMPORT_JOB_COMPLETED")) {
                failImportCompletion = false;
                throw new IllegalStateException("Injected import audit failure");
            }
            delegate.append(event);
        }

        void failNextImportCompletion() {
            failImportCompletion = true;
        }

        void reset() {
            failImportCompletion = false;
        }
    }
}
