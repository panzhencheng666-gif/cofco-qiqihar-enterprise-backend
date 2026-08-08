package com.cofco.qiqihar.graintrade.importing.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.importing.application.ProductionImportTemplate;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Import(ProductionImportConcurrencyIntegrationTest.ConcurrencyConfiguration.class)
class ProductionImportConcurrencyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        truncateImportEffects();
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
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> submit(start, csv));
            Future<MvcResult> second = executor.submit(() -> submit(start, csv));

            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(count("platform.import_job")).isEqualTo(1);
            assertThat(count("platform.import_row_result")).isEqualTo(1);
            assertThat(count("production.production_record")).isEqualTo(1);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(jobId(firstResult)).isEqualTo(jobId(secondResult));
        }

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE action_code IN ('PRODUCTION_RECORD_CREATED', 'IMPORT_JOB_COMPLETED')
                """).query(Long.class).single()).isEqualTo(2);
    }

    private MvcResult submit(CyclicBarrier start, byte[] csv) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return mvc.perform(multipart("/api/v1/imports/production")
                        .file(new MockMultipartFile("file", "production.csv", "text/csv", csv))
                        .header("Idempotency-Key", "atomic-concurrent-import")
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
                Map.entry("PROD_SAMPLE_LATITUDE", "47.3543"), Map.entry("PROD_SAMPLE_LONGITUDE", "123.9182")));
        values.putAll(overrides);
        return ProductionImportTemplate.HEADERS.stream().map(values::get).collect(java.util.stream.Collectors.joining(","));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void truncateImportEffects() {
        JdbcClient.create(dataSource).sql("""
                TRUNCATE platform.import_row_result,platform.import_job,platform.business_audit_event,
                  production.production_record RESTART IDENTITY CASCADE
                """).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyConfiguration {
        @Bean
        @Primary
        ProductionImportPort coordinatedProductionImportPort(ProductionRecordService delegate) {
            return new CoordinatedProductionImportPort(delegate);
        }
    }

    private static final class CoordinatedProductionImportPort implements ProductionImportPort {
        private final ProductionImportPort delegate;
        private final CountDownLatch firstCalls = new CountDownLatch(2);

        private CoordinatedProductionImportPort(ProductionImportPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public String importDraft(ProductionDraft draft) {
            firstCalls.countDown();
            try {
                firstCalls.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent import test was interrupted", exception);
            }
            return delegate.importDraft(draft);
        }
    }
}
