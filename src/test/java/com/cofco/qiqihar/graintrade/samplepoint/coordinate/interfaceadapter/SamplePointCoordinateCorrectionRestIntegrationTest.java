package com.cofco.qiqihar.graintrade.samplepoint.coordinate.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.samplepoint.coordinate.application.SamplePointCoordinateCorrectionWorkbook;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Transactional
class SamplePointCoordinateCorrectionRestIntegrationTest {
    private static final UUID FIRST = UUID.fromString("95000000-0000-0000-0000-000000000201");
    private static final UUID SECOND = UUID.fromString("95000000-0000-0000-0000-000000000202");
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE registry.sample_point RESTART IDENTITY CASCADE").update();
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230202',
                  ST_Multi(ST_GeomFromText('POLYGON((123 47,124 47,124 48,123 48,123 47))',4326)),
                  'coordinate correction fixture','urn:test:sample-point-coordinate-correction','test-v1',
                  'Test fixture','230202',DATE '2026-08-20',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=excluded.geometry,source_name=excluded.source_name,
                  source_url=excluded.source_url,source_revision=excluded.source_revision,
                  source_license=excluded.source_license,source_feature_id=excluded.source_feature_id,
                  source_effective_on=excluded.source_effective_on,geometry_sha256=excluded.geometry_sha256
                """).update();
        insertPoint(FIRST, "重复坐标甲");
        insertPoint(SECOND, "重复坐标乙");
    }

    @Test
    void exportsUploadsAndIndependentlyAppliesOneInPlaceCorrection() throws Exception {
        long pointCount = count("registry.sample_point");
        long productionCount = count("production.production_record");
        long marketCount = count("market.market_record");
        byte[] exported = mockMvc.perform(get("/api/v1/sample-point-coordinate-corrections/export")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        var parsed = SamplePointCoordinateCorrectionWorkbook.read(exported);
        assertThat(parsed.rows()).extracting(SamplePointCoordinateCorrectionWorkbook.Row::samplePointId)
                .containsExactlyInAnyOrder(FIRST, SECOND);

        List<SamplePointCoordinateCorrectionWorkbook.Row> corrected = new ArrayList<>();
        for (var row : parsed.rows()) {
            if (row.samplePointId().equals(FIRST)) {
                corrected.add(withDecision(row, SamplePointCoordinateCorrectionWorkbook.KEEP,
                        null, null, "现场定位复核", "确认该点保留原坐标"));
            } else {
                corrected.add(withDecision(row, SamplePointCoordinateCorrectionWorkbook.CHANGE,
                        new BigDecimal("123.5201"), new BigDecimal("47.9301"),
                        "现场重新定位", "已核对实际经营地址"));
            }
        }
        byte[] upload = SamplePointCoordinateCorrectionWorkbook.create(parsed.batchId(), corrected);
        MockMultipartFile file = new MockMultipartFile("file", "坐标修正.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", upload);
        String requestId = mockMvc.perform(multipart("/api/v1/sample-point-coordinate-corrections")
                        .file(file).header("Idempotency-Key", "coordinate-correction-happy-1")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.totalRows").value(2))
                .andExpect(jsonPath("$.data.pendingReviewRows").value(1))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"requestId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        assertThat(count("registry.sample_point")).isEqualTo(pointCount);
        assertThat(count("production.production_record")).isEqualTo(productionCount);
        assertThat(count("market.market_record")).isEqualTo(marketCount);
        assertThat(coordinate(SECOND)).isEqualTo("123.51|47.92|0");

        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests/{id}/review", requestId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"自行复核\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("SAMPLE_POINT_CORRECTION_SELF_REVIEW_FORBIDDEN"));
        grantAccountOwner("production-tester");
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests/{id}/review", requestId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"唯一所有者现场依据完整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("APPLIED"));

        assertThat(coordinate(SECOND)).isEqualTo("123.5201|47.9301|1");
        assertThat(count("registry.sample_point")).isEqualTo(pointCount);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE action_code='SAMPLE_POINT_COORDINATE_CORRECTION_APPLIED'
                  AND aggregate_id=:requestId
                """).param("requestId", requestId).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE action_code='SAMPLE_POINT_COORDINATE_CORRECTION_APPLIED'
                  AND aggregate_id=:requestId
                  AND actor_subject_id='production-tester'
                  AND detail->>'privilegedSelfReview'='true'
                """).param("requestId", requestId).query(Long.class).single()).isOne();
        mockMvc.perform(get("/api/v1/sample-point-coordinate-corrections/history")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].statusCode").value("PENDING_REVIEW"));
    }

    @Test
    void accountOwnerCanRejectOwnCorrectionWithExplicitPrivilegeAudit() throws Exception {
        byte[] exported = mockMvc.perform(get("/api/v1/sample-point-coordinate-corrections/export")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var parsed = SamplePointCoordinateCorrectionWorkbook.read(exported);
        List<SamplePointCoordinateCorrectionWorkbook.Row> corrected = parsed.rows().stream()
                .map(row -> row.samplePointId().equals(FIRST)
                        ? withDecision(row, SamplePointCoordinateCorrectionWorkbook.KEEP,
                                null, null, "现场定位复核", "确认该点保留原坐标")
                        : withDecision(row, SamplePointCoordinateCorrectionWorkbook.CHANGE,
                                new BigDecimal("123.5201"), new BigDecimal("47.9301"),
                                "现场重新定位", "已核对实际经营地址"))
                .toList();
        byte[] upload = SamplePointCoordinateCorrectionWorkbook.create(parsed.batchId(), corrected);
        MockMultipartFile file = new MockMultipartFile("file", "坐标修正-驳回.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", upload);
        String requestId = mockMvc.perform(multipart("/api/v1/sample-point-coordinate-corrections")
                        .file(file).header("Idempotency-Key", "coordinate-correction-owner-reject-1")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"requestId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        grantAccountOwner("production-tester");
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests/{id}/review", requestId)
                        .principal(() -> "production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"唯一所有者要求重新核验\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("REJECTED"));

        assertThat(coordinate(SECOND)).isEqualTo("123.51|47.92|0");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE action_code='SAMPLE_POINT_COORDINATE_CORRECTION_REJECTED'
                  AND aggregate_id=:requestId
                  AND actor_subject_id='production-tester'
                  AND detail->>'privilegedSelfReview'='true'
                """).param("requestId", requestId).query(Long.class).single()).isOne();
    }

    @Test
    void rejectsSelfReviewAndPersistsGroupValidationErrorsWithoutChangingPoints() throws Exception {
        byte[] exported = mockMvc.perform(get("/api/v1/sample-point-coordinate-corrections/export")
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var parsed = SamplePointCoordinateCorrectionWorkbook.read(exported);
        List<SamplePointCoordinateCorrectionWorkbook.Row> invalid = parsed.rows().stream()
                .map(row -> withDecision(row, SamplePointCoordinateCorrectionWorkbook.KEEP,
                        null, null, "现场复核", "均错误保留"))
                .toList();
        byte[] upload = SamplePointCoordinateCorrectionWorkbook.create(parsed.batchId(), invalid);
        MockMultipartFile file = new MockMultipartFile("file", "无效坐标修正.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", upload);
        String jobId = mockMvc.perform(multipart("/api/v1/sample-point-coordinate-corrections")
                        .file(file).header("Idempotency-Key", "coordinate-correction-invalid-1")
                        .principal(() -> "production-tester"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.data.failedRows").value(2))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"jobId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mockMvc.perform(get("/api/v1/sample-point-coordinate-corrections/jobs/{id}/errors", jobId)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("每个重复坐标组必须且只能保留一个原坐标"));
        assertThat(coordinate(FIRST)).isEqualTo("123.51|47.92|0");
        assertThat(coordinate(SECOND)).isEqualTo("123.51|47.92|0");
    }

    private void insertPoint(UUID id, String name) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE',:name,'230202','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.51,47.92),4326),DATE '2026-01-01',0,
                  'production-tester','production-tester')
                """).param("id", id).param("name", name).update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String coordinate(UUID id) {
        return jdbc.sql("""
                SELECT ST_X(governed_point)::text || '|' || ST_Y(governed_point)::text || '|' || version
                FROM registry.sample_point WHERE sample_point_id=:id
                """).param("id", id).query(String.class).single();
    }

    private void grantAccountOwner(String subjectId) {
        jdbc.sql("DELETE FROM platform.security_user_role WHERE role_code='ACCOUNT_OWNER'").update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:subjectId,'ACCOUNT_OWNER')
                """).param("subjectId", subjectId).update();
    }

    private static SamplePointCoordinateCorrectionWorkbook.Row withDecision(
            SamplePointCoordinateCorrectionWorkbook.Row row, String action,
            BigDecimal longitude, BigDecimal latitude, String source, String note) {
        return new SamplePointCoordinateCorrectionWorkbook.Row(
                row.samplePointId(), row.expectedVersion(), row.canonicalName(), row.regionCode(),
                row.regionName(), row.kindCode(), row.originalLongitude(), row.originalLatitude(),
                row.duplicateGroupId(), row.rowBinding(), action, longitude, latitude, source, note);
    }
}
