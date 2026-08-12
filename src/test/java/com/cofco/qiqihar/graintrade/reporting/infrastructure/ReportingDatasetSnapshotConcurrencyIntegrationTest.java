package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.reporting.application.ReportPreviewCommand;
import com.cofco.qiqihar.graintrade.reporting.application.ReportingRepository;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class ReportingDatasetSnapshotConcurrencyIntegrationTest {
    @Autowired ReportingRepository repository;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    private JdbcClient jdbc;

    @BeforeEach
    void cleanSources() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE production.production_record,market.market_record,
                  logistics.route_event,supply.calculation_run RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(
                  code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603,'2026/27')
                ON CONFLICT(code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES('SNAP-A','快照起点','RAIL_NODE','230200'),
                      ('SNAP-B','快照终点','RAIL_NODE','230202')
                ON CONFLICT(node_code) DO NOTHING
                """).update();
    }

    @AfterEach
    void removeConcurrentSources() {
        jdbc.sql("""
                TRUNCATE production.production_record,market.market_record,
                  logistics.route_event,supply.calculation_run RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void countCutoffAndManifestShareOneStatementSnapshotAcrossAllFourDomains() throws Exception {
        for (Domain domain : Domain.values()) {
            verifyWhileSecondConnectionCommits(domain);
        }
    }

    private void verifyWhileSecondConnectionCommits(Domain domain) throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        CountDownLatch firstCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                executor.submit(() -> {
                    try (Connection connection = dataSource.getConnection();
                            Statement session = connection.createStatement()) {
                        if (domain == Domain.SUPPLY) {
                            session.execute("SET session_replication_role=replica");
                        }
                        JdbcClient writer = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                        int sequence = 0;
                        while (running.get()) {
                            domain.insert(writer, sequence++, Instant.parse("2026-08-01T00:00:00Z")
                                    .plus(sequence, ChronoUnit.SECONDS));
                            firstCommitted.countDown();
                        }
                        if (domain == Domain.SUPPLY) {
                            session.execute("SET session_replication_role=origin");
                        }
                    } catch (Throwable failure) {
                        writerFailure.set(failure);
                        firstCommitted.countDown();
                    }
                });
                firstCommitted.await();
                assertThat(writerFailure.get()).isNull();

                for (int attempt = 0; attempt < 12; attempt++) {
                    var material = repository.loadPreviewMaterial(new ReportPreviewCommand(
                            domain.definition, "CORN", null, "PREFECTURE", "230200", "2026-Q3"));
                    JsonNode snapshot = json.readTree(material.approvedSummaryJson());
                    JsonNode sources = snapshot.path("sources");
                    assertThat(material.approvedRecordCount())
                            .as(domain + " count must equal the exact manifest length")
                            .isEqualTo(sources.size());
                    Instant maximumReportedAt = maximumReportedAt(sources);
                    assertThat(maximumReportedAt).as(domain + " must exercise at least one source row").isNotNull();
                    assertThat(material.dataCutoff())
                            .as(domain + " cutoff must equal the maximum manifest timestamp")
                            .isEqualTo(maximumReportedAt);
                    assertThat(Instant.parse(snapshot.path("dataCutoff").asText()))
                            .isEqualTo(maximumReportedAt);
                }
            } finally {
                running.set(false);
            }
        }
        assertThat(writerFailure.get()).isNull();
    }

    private Instant maximumReportedAt(JsonNode sources) {
        Instant maximum = null;
        for (JsonNode source : sources) {
            Instant candidate = Instant.parse(source.path("reportedAt").asText());
            if (maximum == null || candidate.isAfter(maximum)) maximum = candidate;
        }
        return maximum;
    }

    private enum Domain {
        PRODUCTION("PRODUCTION_DAILY") {
            @Override void insert(JdbcClient jdbc, int sequence, Instant reportedAt) {
                jdbc.sql("""
                        INSERT INTO production.production_record(
                          record_id,product_code,object_type_code,region_code,survey_date,reported_at,
                          cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                        VALUES(:id,'CORN','FARMER','230200',DATE '2026-08-01',:reportedAt,
                          100,20,'APPROVED','snapshot-writer')
                        """).param("id", UUID.randomUUID().toString())
                        .param("reportedAt", Timestamp.from(reportedAt)).update();
            }
        },
        MARKET("MARKET_DAILY") {
            @Override void insert(JdbcClient jdbc, int sequence, Instant reportedAt) {
                jdbc.sql("""
                        INSERT INTO market.market_record(
                          record_id,product_code,object_type_code,region_code,trade_date,reported_at,
                          purchase_base_price,trade_direction,status_code,last_modified_by)
                        VALUES(:id,'CORN','TRADER','230200',DATE '2026-08-01',:reportedAt,
                          2200,'PURCHASE','APPROVED','snapshot-writer')
                        """).param("id", UUID.randomUUID().toString())
                        .param("reportedAt", Timestamp.from(reportedAt)).update();
            }
        },
        LOGISTICS("LOGISTICS_DAILY") {
            @Override void insert(JdbcClient jdbc, int sequence, Instant reportedAt) {
                jdbc.sql("""
                        INSERT INTO logistics.route_event(
                          event_id,product_code,monitoring_period_code,collection_date,reported_at,
                          origin_region_code,origin_node_id,origin_node_code,
                          destination_region_code,destination_node_id,destination_node_code,
                          transport_mode_code,direction_code,source_organization,reporter,status_code,
                          created_by,last_modified_by,created_at,updated_at,survey_year,survey_month,
                          survey_period_precision,survey_period_governance_state)
                        SELECT CAST(:id AS uuid),'CORN','2026-Q3',DATE '2026-08-01',:reportedAt,
                          origin.region_code,origin.node_id,origin.node_code,
                          destination.region_code,destination.node_id,destination.node_code,
                          'RAIL','OUTFLOW','快照测试','snapshot-writer','APPROVED',
                          'snapshot-writer','snapshot-writer',:reportedAt,:reportedAt,2026,8,
                          'YEAR_MONTH','CONFIRMED'
                        FROM logistics.logistics_node origin CROSS JOIN logistics.logistics_node destination
                        WHERE origin.node_code='SNAP-A' AND destination.node_code='SNAP-B'
                        """).param("id", UUID.randomUUID().toString())
                        .param("reportedAt", Timestamp.from(reportedAt)).update();
            }
        },
        SUPPLY("SUPPLY_DAILY") {
            @Override void insert(JdbcClient jdbc, int sequence, Instant reportedAt) {
                jdbc.sql("""
                        INSERT INTO supply.calculation_run(
                          calculation_run_id,product_code,region_code,marketing_year,formula_version_id,
                          result_state,validation_codes,total_supply,total_use,calculated_ending_inventory,
                          adopted_ending_inventory,balanced,created_by,created_at,version,formula_snapshot,
                          period_code,survey_year,survey_quarter,period_precision,temporal_governance_state)
                        VALUES(CAST(:id AS uuid),'CORN','230200','2026/27',
                          (SELECT formula_version_id FROM supply.formula_version ORDER BY formula_version_id LIMIT 1),
                          'PUBLISHED',ARRAY[]::text[],10,8,2,2,true,'snapshot-writer',:reportedAt,
                          :version,'{}'::jsonb,'2026-Q3',2026,'Q3','QUARTER','CONFIRMED')
                        """).param("id", UUID.randomUUID().toString())
                        .param("reportedAt", Timestamp.from(reportedAt)).param("version", sequence).update();
            }
        };

        private final String definition;
        Domain(String definition) { this.definition = definition; }
        abstract void insert(JdbcClient jdbc, int sequence, Instant reportedAt);
    }
}
