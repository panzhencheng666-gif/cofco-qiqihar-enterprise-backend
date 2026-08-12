package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.ProtectedTestDatabaseConfiguration;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = GrainTradeApplication.class)
@UsesProtectedTestDatabase
class MasterDataGovernanceIntegrationTest {
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        ProtectedTestDatabaseConfiguration.provisionSecurityTestSubjects(jdbc);
    }

    @Test
    void exposesCanonicalRegionProductObjectTypeAndStableSubjectWithFormalLineage() {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES('97000000-0000-0000-0000-000000000001','SURVEY_SITE','统一主体测试点',
                  '230208','APPROVED','MISSING',DATE '2026-01-01','production-tester','production-tester')
                ON CONFLICT(sample_point_id) DO NOTHING
                """).update();
        govern("SUBJECT", "PRODUCTION:governed-subject-1", "INSERT", """
                {"business_domain":"PRODUCTION","subject_id":"governed-subject-1",
                 "sample_point_id":"97000000-0000-0000-0000-000000000001",
                 "created_at":"2026-08-12T18:00:00+08:00","created_by":"production-tester"}
                """);

        assertCanonical("REGION", "230208", "梅里斯达斡尔族区", "platform.region", "230208");
        assertCanonical("PRODUCT", "CORN", "玉米", "platform.product", "CORN");
        assertCanonical("OBJECT_TYPE", "FARMER", "农户", "platform.object_type", "FARMER");
        assertCanonical("SUBJECT", "PRODUCTION:governed-subject-1", "governed-subject-1",
                "registry.sample_point_subject_identity", "97000000-0000-0000-0000-000000000001");
    }

    @Test
    void requiresSeparatedReviewAndAnEffectiveBoundaryBeforeControlledApply() throws Exception {
        String original = snapshot("PRODUCT", "CORN");
        String changed = withField(original, "name", "玉米（治理核对）");
        long requestId = submit(
                "PRODUCT", "CORN", "UPDATE", changed, "clock_timestamp() + interval '250 milliseconds'");

        assertThatThrownBy(() -> review(requestId, "APPROVE", "production-tester", "申请人自审"))
                .hasMessageContaining("separation of duties");
        review(requestId, "APPROVE", "market-tester", "双人复核：名称与稳定编码一致");
        assertThatThrownBy(() -> apply(requestId, "supply-reviewer"))
                .hasMessageContaining("effective_at");
        Thread.sleep(300);

        int before = revision("PRODUCT", "CORN");
        assertThat(apply(requestId, "supply-reviewer")).isTrue();
        assertThat(apply(requestId, "supply-reviewer")).isFalse();
        assertThat(revision("PRODUCT", "CORN")).isEqualTo(before + 1);
        var revision = jdbc.sql("""
                SELECT snapshot->>'name' AS name,changed_by,reviewed_by,review_basis,change_request_id
                FROM platform.master_data_revision
                WHERE entity_type='PRODUCT' AND entity_key='CORN' ORDER BY revision_no DESC LIMIT 1
                """).query().singleRow();
        assertThat(revision.get("name")).isEqualTo("玉米（治理核对）");
        assertThat(revision.get("changed_by")).isEqualTo("supply-reviewer");
        assertThat(revision.get("reviewed_by")).isEqualTo("market-tester");
        assertThat(revision.get("review_basis")).isEqualTo("双人复核：名称与稳定编码一致");
        assertThat(((Number) revision.get("change_request_id")).longValue()).isEqualTo(requestId);
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE platform.master_data_revision SET changed_by='tampered'
                WHERE entity_type='PRODUCT' AND entity_key='CORN'
                """).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE platform.master_data_change_request SET requested_by='tampered'
                WHERE request_id=:requestId
                """).param("requestId", requestId).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE platform.master_data_change_event SET actor='tampered'
                WHERE request_id=:requestId
                """).param("requestId", requestId).update()).hasMessageContaining("append-only");

        govern("PRODUCT", "CORN", "UPDATE", original);
    }

    @Test
    void rejectsDirectWritesAndGovernsRegionProductObjectTypeAndSubjectAdjacently() {
        String subjectKey = "PRODUCTION:governed-subject-2";
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  effective_from,created_by,updated_by)
                VALUES('97000000-0000-0000-0000-000000000002','SURVEY_SITE','统一主体测试点2',
                  '230208','APPROVED','MISSING',DATE '2026-01-01','production-tester','production-tester')
                ON CONFLICT(sample_point_id) DO NOTHING
                """).update();
        govern("SUBJECT", subjectKey, "INSERT", """
                {"business_domain":"PRODUCTION","subject_id":"governed-subject-2",
                 "sample_point_id":"97000000-0000-0000-0000-000000000002",
                 "created_at":"2026-08-12T18:00:00+08:00","created_by":"production-tester"}
                """);

        assertThatThrownBy(() -> jdbc.sql("UPDATE platform.region SET name='绕过治理' WHERE code='230208'").update())
                .hasMessageContaining("controlled apply");
        assertThatThrownBy(() -> jdbc.sql("UPDATE platform.product SET name='绕过治理' WHERE code='CORN'").update())
                .hasMessageContaining("controlled apply");
        assertThatThrownBy(() -> jdbc.sql("UPDATE platform.object_type SET name='绕过治理' WHERE code='FARMER'").update())
                .hasMessageContaining("controlled apply");
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE registry.sample_point_subject_identity SET created_by='market-tester'
                WHERE business_domain='PRODUCTION' AND subject_id='governed-subject-2'
                """).update()).hasMessageContaining("controlled apply");

        List<Change> changes = List.of(
                change("REGION", "230208", "name", "梅里斯达斡尔族区（治理核对）"),
                change("PRODUCT", "CORN", "name", "玉米（治理核对）"),
                change("OBJECT_TYPE", "FARMER", "name", "农户（治理核对）"),
                change("SUBJECT", subjectKey, "created_by", "market-tester"));
        changes.forEach(change -> govern(change.type(), change.key(), "UPDATE", change.changedSnapshot()));

        assertThat(snapshot("REGION", "230208")).contains("梅里斯达斡尔族区（治理核对）");
        assertThat(snapshot("PRODUCT", "CORN")).contains("玉米（治理核对）");
        assertThat(snapshot("OBJECT_TYPE", "FARMER")).contains("农户（治理核对）");
        assertThat(snapshot("SUBJECT", subjectKey)).contains("market-tester");
        changes.forEach(change -> govern(change.type(), change.key(), "UPDATE", change.originalSnapshot()));

        long rejected = submit("PRODUCT", "CORN", "UPDATE", withField(snapshot("PRODUCT", "CORN"),
                "name", "不得生效"), "clock_timestamp()");
        review(rejected, "REJECT", "market-tester", "名称依据不足");
        assertThatThrownBy(() -> apply(rejected, "supply-reviewer"))
                .hasMessageContaining("approved review");
        assertThat(snapshot("PRODUCT", "CORN")).doesNotContain("不得生效");
    }

    private Change change(String type, String key, String field, String value) {
        String original = snapshot(type, key);
        return new Change(type, key, original, withField(original, field, value));
    }

    private long submit(String type, String key, String operation, String snapshot, String effectiveExpression) {
        return jdbc.sql("SELECT platform.submit_master_data_change(:type,:key,:operation,"
                        + "CAST(:snapshot AS jsonb)," + effectiveExpression + ",:applicant,:basis)")
                .param("type", type).param("key", key).param("operation", operation)
                .param("snapshot", snapshot).param("applicant", "production-tester")
                .param("basis", "自动化主数据治理申请")
                .query(Long.class).single();
    }

    private void review(long requestId, String decision, String reviewer, String basis) {
        jdbc.sql("SELECT platform.review_master_data_change(:requestId,:decision,:reviewer,:basis)")
                .param("requestId", requestId).param("decision", decision)
                .param("reviewer", reviewer).param("basis", basis).query(Boolean.class).single();
    }

    private boolean apply(long requestId, String actor) {
        return jdbc.sql("SELECT platform.apply_master_data_change(:requestId,:actor)")
                .param("requestId", requestId).param("actor", actor).query(Boolean.class).single();
    }

    private long govern(String type, String key, String operation, String snapshot) {
        return jdbc.sql("""
                SELECT platform.govern_master_data_change(
                  :type,:key,:operation,CAST(:snapshot AS jsonb),clock_timestamp(),
                  'production-tester','market-tester','自动化双人复核')
                """).param("type", type).param("key", key).param("operation", operation)
                .param("snapshot", snapshot).query(Long.class).single();
    }

    private String snapshot(String type, String key) {
        String sql = switch (type) {
            case "REGION" -> "SELECT to_jsonb(row)::text FROM platform.region row WHERE code=:key";
            case "PRODUCT" -> "SELECT to_jsonb(row)::text FROM platform.product row WHERE code=:key";
            case "OBJECT_TYPE" -> "SELECT to_jsonb(row)::text FROM platform.object_type row WHERE code=:key";
            case "SUBJECT" -> """
                    SELECT to_jsonb(row)::text FROM registry.sample_point_subject_identity row
                    WHERE business_domain=split_part(:key,':',1)
                      AND subject_id=substring(:key from position(':' in :key)+1)
                    """;
            default -> throw new IllegalArgumentException(type);
        };
        return jdbc.sql(sql).param("key", key).query(String.class).single();
    }

    private String withField(String snapshot, String field, String value) {
        return jdbc.sql("SELECT (CAST(:snapshot AS jsonb) || jsonb_build_object(:field,:value))::text")
                .param("snapshot", snapshot).param("field", field).param("value", value)
                .query(String.class).single();
    }

    private record Change(String type, String key, String originalSnapshot, String changedSnapshot) {}

    private void assertCanonical(String type, String key, String name, String relation, String sourceKey) {
        var row = jdbc.sql("""
                SELECT display_name,source_relation,source_key,governance_state,revision_no
                FROM platform.canonical_master_data
                WHERE entity_type=:type AND entity_key=:key
                """).param("type", type).param("key", key).query().singleRow();
        assertThat(row.get("display_name")).isEqualTo(name);
        assertThat(row.get("source_relation")).isEqualTo(relation);
        assertThat(row.get("source_key")).isEqualTo(sourceKey);
        assertThat(row.get("governance_state")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("revision_no")).intValue()).isPositive();
    }

    private int revision(String type, String key) {
        return jdbc.sql("""
                SELECT max(revision_no) FROM platform.master_data_revision
                WHERE entity_type=:type AND entity_key=:key
                """).param("type", type).param("key", key).query(Integer.class).single();
    }
}
