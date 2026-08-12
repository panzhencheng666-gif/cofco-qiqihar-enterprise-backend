package com.cofco.qiqihar.graintrade.registry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class SampleSubjectResolutionMigrationIntegrationTest {
    private static final String ACTOR = "production-tester";
    private static final String POINT = "96000000-0000-0000-0000-000000000001";
    private static final String PRODUCTION = "96000000-0000-0000-0000-000000000101";
    private static final String MARKET = "96000000-0000-0000-0000-000000000201";

    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        clearRehearsalData();
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES(CAST(:point AS uuid),'SURVEY_SITE','显式稳定主体目标','230208','APPROVED','MISSING',
                  DATE '2026-01-01',:actor,:actor)
                """).param("point", POINT).param("actor", ACTOR).update();
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,'CORN','FARMER','230208',DATE '2026-08-05',
                  TIMESTAMPTZ '2026-08-06 08:00:00+08',10,20,'APPROVED',:actor)
                """).param("id", PRODUCTION).param("actor", ACTOR).update();
        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,
                  reported_at,purchase_base_price,trade_direction,carriage_board_amount,freight_amount,
                  status_code,last_modified_by)
                VALUES(:id,'CORN','TRADER','230208',DATE '2026-08-05',
                  TIMESTAMPTZ '2026-08-06 09:00:00+08',2500,'PURCHASE',0,0,'APPROVED',:actor)
                """).param("id", MARKET).param("actor", ACTOR).update();
    }

    @AfterEach
    void tearDown() {
        clearRehearsalData();
    }

    private void clearRehearsalData() {
        jdbc.sql("""
                TRUNCATE registry.sample_subject_resolution_audit,
                  registry.sample_subject_resolution_item,
                  registry.sample_subject_resolution_batch,registry.sample_point,
                  production.production_record,market.market_record RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void rehearsalRoundOneLinksByExplicitIdsIsIdempotentAndRollsBackExactly() {
        String productionFactSha256 = factSha256("production.production_record", PRODUCTION);
        String batch = stage("round-one", "PRODUCTION", PRODUCTION, "LINK", "subject-production-1", POINT, 0);

        assertThat(apply(batch)).isEqualTo("APPLIED");
        assertThat(apply(batch)).isEqualTo("ALREADY_APPLIED");

        assertThat(value("SELECT sample_point_id::text FROM production.production_record WHERE record_id=:id", PRODUCTION))
                .isNull();
        assertThat(value("SELECT value FROM production.production_record_submission_metadata WHERE record_id=:id AND field_code='PROD_SAMPLE_SUBJECT_CODE'", PRODUCTION))
                .isNull();
        assertThat(value("SELECT target_sample_point_id::text FROM registry.current_sample_subject_resolution WHERE source_record_id=:id", PRODUCTION))
                .isEqualTo(POINT);
        assertThat(value("SELECT stable_subject_id FROM registry.current_sample_subject_resolution WHERE source_record_id=:id", PRODUCTION))
                .isEqualTo("subject-production-1");
        assertThat(count("SELECT count(*) FROM registry.sample_subject_resolution_revision WHERE source_record_id=:id", PRODUCTION))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM registry.sample_subject_resolution_item WHERE batch_id=CAST(:id AS uuid) AND status_code='APPLIED'", batch))
                .isEqualTo(1);
        assertThat(value("SELECT version::text FROM production.production_record WHERE record_id=:id", PRODUCTION))
                .isEqualTo("0");
        assertThat(factSha256("production.production_record", PRODUCTION)).isEqualTo(productionFactSha256);
        assertCanonicalItemHashes(batch);

        assertThat(rollback(batch)).isEqualTo("ROLLED_BACK");
        assertThat(rollback(batch)).isEqualTo("ALREADY_ROLLED_BACK");

        assertThat(value("SELECT sample_point_id::text FROM production.production_record WHERE record_id=:id", PRODUCTION))
                .isNull();
        assertThat(count("SELECT count(*) FROM production.production_record_submission_metadata WHERE record_id=:id AND field_code='PROD_SAMPLE_SUBJECT_CODE'", PRODUCTION))
                .isZero();
        assertThat(count("SELECT count(*) FROM registry.current_sample_subject_resolution WHERE source_record_id=:id", PRODUCTION))
                .isZero();
        assertThat(count("SELECT count(*) FROM registry.sample_subject_resolution_revision WHERE source_record_id=:id", PRODUCTION))
                .isEqualTo(2);
        assertThat(value("SELECT version::text FROM production.production_record WHERE record_id=:id", PRODUCTION))
                .isEqualTo("0");
        assertThat(factSha256("production.production_record", PRODUCTION)).isEqualTo(productionFactSha256);
        assertThat(value("SELECT status_code FROM registry.sample_subject_resolution_batch WHERE batch_id=CAST(:id AS uuid)", batch))
                .isEqualTo("ROLLED_BACK");
    }

    @Test
    void rehearsalRoundTwoLinksAndVoidsAtomicallyThenRejectsStaleReplay() {
        String productionFactSha256 = factSha256("production.production_record", PRODUCTION);
        String marketFactSha256 = factSha256("market.market_record", MARKET);
        String batch = UUID.randomUUID().toString();
        insertBatch(batch, "round-two", 2);
        insertItem(batch, 1, "MARKET", MARKET, "LINK", "subject-market-1", POINT, 0);
        insertItem(batch, 2, "PRODUCTION", PRODUCTION, "VOID", null, null, 0);

        assertThat(apply(batch)).isEqualTo("APPLIED");
        assertThat(apply(batch)).isEqualTo("ALREADY_APPLIED");

        assertThat(value("SELECT target_sample_point_id::text FROM registry.current_sample_subject_resolution WHERE source_record_id=:id", MARKET))
                .isEqualTo(POINT);
        assertThat(value("SELECT resolution_action FROM registry.current_sample_subject_resolution WHERE source_record_id=:id", PRODUCTION))
                .isEqualTo("VOID");
        assertThat(value("SELECT status_code FROM production.production_record WHERE record_id=:id", PRODUCTION))
                .isEqualTo("APPROVED");
        assertThat(value("SELECT sample_point_id::text FROM market.market_record WHERE record_id=:id", MARKET))
                .isNull();
        assertThat(count("SELECT count(*) FROM registry.sample_subject_resolution_audit WHERE batch_id=CAST(:id AS uuid) AND action_code='APPLIED'", batch))
                .isEqualTo(1);
        assertThat(factSha256("production.production_record", PRODUCTION)).isEqualTo(productionFactSha256);
        assertThat(factSha256("market.market_record", MARKET)).isEqualTo(marketFactSha256);
        assertCanonicalItemHashes(batch);

        assertThat(rollback(batch)).isEqualTo("ROLLED_BACK");
        assertThat(rollback(batch)).isEqualTo("ALREADY_ROLLED_BACK");
        assertThat(value("SELECT status_code FROM production.production_record WHERE record_id=:id", PRODUCTION))
                .isEqualTo("APPROVED");
        assertThat(value("SELECT sample_point_id::text FROM market.market_record WHERE record_id=:id", MARKET))
                .isNull();
        assertThat(count("SELECT count(*) FROM registry.current_sample_subject_resolution WHERE batch_id=CAST(:id AS uuid)", batch))
                .isZero();
        assertThat(count("SELECT count(*) FROM registry.sample_subject_resolution_revision WHERE batch_id=CAST(:id AS uuid)", batch))
                .isEqualTo(4);
        assertThat(factSha256("production.production_record", PRODUCTION)).isEqualTo(productionFactSha256);
        assertThat(factSha256("market.market_record", MARKET)).isEqualTo(marketFactSha256);

        String stale = stage("stale-version", "PRODUCTION", PRODUCTION, "LINK", "subject-stale", POINT, 9);
        assertThatThrownBy(() -> apply(stale)).hasMessageContaining("version mismatch");
        assertThat(value("SELECT status_code FROM registry.sample_subject_resolution_batch WHERE batch_id=CAST(:id AS uuid)", stale))
                .isEqualTo("STAGED");
    }

    @Test
    void stagingContractCannotAcceptNamesContactsOrCoordinatesAndAuditIsAppendOnly() {
        Set<String> columns = Set.copyOf(jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='registry' AND table_name='sample_subject_resolution_item'
                """).query(String.class).list());
        assertThat(columns).doesNotContain("subject_name", "contact", "longitude", "latitude");

        String batch = stage("append-only-audit", "PRODUCTION", PRODUCTION, "VOID", null, null, 0);
        apply(batch);
        assertThat(value("SELECT length(before_sha256)::text FROM registry.sample_subject_resolution_item WHERE batch_id=CAST(:id AS uuid)", batch))
                .isEqualTo("64");
        assertThat(value("SELECT length(after_sha256)::text FROM registry.sample_subject_resolution_item WHERE batch_id=CAST(:id AS uuid)", batch))
                .isEqualTo("64");
        assertThatThrownBy(() -> jdbc.sql("UPDATE registry.sample_subject_resolution_audit SET action_code='ROLLED_BACK'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM registry.sample_subject_resolution_revision").update())
                .hasMessageContaining("append-only");
    }

    private String stage(String key, String domain, String record, String action, String subject,
            String point, long version) {
        String batch = UUID.randomUUID().toString();
        insertBatch(batch, key, 1);
        insertItem(batch, 1, domain, record, action, subject, point, version);
        return batch;
    }

    private void insertBatch(String batch, String key, int count) {
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_batch(
                  batch_id,idempotency_key,input_digest,expected_item_count,status_code,created_at,created_by)
                VALUES(CAST(:batch AS uuid),:key,repeat('a',64),:count,'STAGED',now(),:actor)
                """).param("batch", batch).param("key", key).param("count", count)
                .param("actor", ACTOR).update();
    }

    private void insertItem(String batch, int sequence, String domain, String record, String action,
            String subject, String point, long version) {
        jdbc.sql("""
                INSERT INTO registry.sample_subject_resolution_item(
                  batch_id,item_sequence,source_domain,source_record_id,expected_source_version,
                  resolution_action,stable_subject_id,target_sample_point_id,reason_code,status_code)
                VALUES(CAST(:batch AS uuid),:sequence,:domain,:record,:version,:action,:subject,
                  CAST(:point AS uuid),'EXT_007_EXPLICIT_DISPOSITION','STAGED')
                """).param("batch", batch).param("sequence", sequence).param("domain", domain)
                .param("record", record).param("version", version).param("action", action)
                .param("subject", subject).param("point", point).update();
    }

    private String apply(String batch) {
        return jdbc.sql("SELECT registry.apply_sample_subject_resolution(CAST(:id AS uuid),:actor)")
                .param("id", batch).param("actor", ACTOR).query(String.class).single();
    }

    private String rollback(String batch) {
        return jdbc.sql("SELECT registry.rollback_sample_subject_resolution(CAST(:id AS uuid),:actor)")
                .param("id", batch).param("actor", ACTOR).query(String.class).single();
    }

    private String factSha256(String table, String id) {
        return value("SELECT encode(sha256(convert_to(to_jsonb(fact)::text,'UTF8')),'hex') "
                + "FROM " + table + " fact WHERE record_id=:id", id);
    }

    private void assertCanonicalItemHashes(String batch) {
        assertThat(count("""
                SELECT count(*) FROM registry.sample_subject_resolution_item
                WHERE batch_id=CAST(:id AS uuid)
                  AND before_sha256=encode(sha256(convert_to(before_snapshot::text,'UTF8')),'hex')
                  AND after_sha256=encode(sha256(convert_to(after_snapshot::text,'UTF8')),'hex')
                """, batch)).isEqualTo(count("""
                SELECT count(*) FROM registry.sample_subject_resolution_item
                WHERE batch_id=CAST(:id AS uuid)
                """, batch));
    }

    private String value(String sql, String id) {
        return jdbc.sql(sql).param("id", id).query(String.class).optional().orElse(null);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long count(String sql, String id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }
}
