package com.cofco.qiqihar.graintrade.samplepoint.coordinate.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Transactional
class FormalSampleCoordinateChangeRestIntegrationTest {
    private static final UUID SAMPLE =
            UUID.fromString("95000000-0000-0000-0000-000000000301");
    private static final UUID OCCUPIED =
            UUID.fromString("95000000-0000-0000-0000-000000000302");
    private static final UUID JAGDAQI =
            UUID.fromString("95000000-0000-0000-0000-000000000303");
    private static final UUID HULUNBEIER =
            UUID.fromString("95000000-0000-0000-0000-000000000304");

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
                  'formal coordinate change fixture','urn:test:formal-coordinate-change','test-v1',
                  'Test fixture','230202',DATE '2026-08-20',repeat('8',64))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=excluded.geometry,source_name=excluded.source_name,
                  source_url=excluded.source_url,source_revision=excluded.source_revision,
                  source_license=excluded.source_license,source_feature_id=excluded.source_feature_id,
                  source_effective_on=excluded.source_effective_on,geometry_sha256=excluded.geometry_sha256
                """).update();
        insertBoundary("232761", "124 50", "125 50", "125 51", "124 51");
        insertBoundary("150700", "118 48", "120 48", "120 50", "118 50");
        insertPoint(SAMPLE, "正式样本坐标变更", "123.51", "47.92");
        insertPoint(OCCUPIED, "已占用坐标", "123.60", "47.60");
        insertPoint(JAGDAQI, "加格达奇正式样本", "124.50", "50.50", "232761");
        insertPoint(HULUNBEIER, "呼伦贝尔正式样本", "119.00", "49.00", "150700");
    }

    @Test
    void submitsAFormalSampleCoordinateChangeForIndependentReviewWithoutWritingLedgers()
            throws Exception {
        long production = count("production.production_record");
        long market = count("market.market_record");
        long logistics = count("logistics.route_event");

        String requestId = mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "formal-coordinate-change-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("123.5201000", "47.9301000", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.samplePointId").value(SAMPLE.toString()))
                .andExpect(jsonPath("$.data.coordinateSource").value("FIELD_GPS"))
                .andExpect(jsonPath("$.data.coordinateCollectedAt").value("2026-08-29T02:00:00Z"))
                .andExpect(jsonPath("$.data.verifiedAddress").value("龙沙区测试村一组"))
                .andExpect(jsonPath("$.data.evidenceReference").value("现场照片20260829-01"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"requestId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        assertThat(requestId).isNotBlank();
        assertThat(coordinate(SAMPLE)).isEqualTo("123.51|47.92|0");
        assertThat(count("production.production_record")).isEqualTo(production);
        assertThat(count("market.market_record")).isEqualTo(market);
        assertThat(count("logistics.route_event")).isEqualTo(logistics);
    }

    @Test
    void rejectsOutOfRangeOutsideRegionOccupiedAndOverPrecisionCoordinates() throws Exception {
        submitInvalid("181", "47.5", "INVALID_SAMPLE_POINT_COORDINATE");
        submitInvalid("123.5", "90.0000001", "INVALID_SAMPLE_POINT_COORDINATE");
        submitInvalid("124.5", "47.5", "SAMPLE_POINT_CORRECTION_OUTSIDE_REGION");
        submitInvalid("123.60", "47.60", "SAMPLE_POINT_COORDINATE_OCCUPIED");
        submitInvalid("123.52010001", "47.9301", "INVALID_SAMPLE_POINT_COORDINATE");
        submitInvalid("0", "0", "SAMPLE_POINT_COORDINATE_PLACEHOLDER");
        assertThat(coordinate(SAMPLE)).isEqualTo("123.51|47.92|0");
    }

    @Test
    void acceptsGovernedRegionsWithoutARegionSpecificHardCodeAndRejectsAnOutOfScopeActor()
            throws Exception {
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "jagdaqi-coordinate-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(JAGDAQI, "124.5000000", "50.5000000",
                                "124.5100000", "50.5100000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regionCode").value("232761"));
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "hulunbeier-coordinate-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(HULUNBEIER, "119.0000000", "49.0000000",
                                "119.0100000", "49.0100000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regionCode").value("150700"));

        jdbc.sql("""
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code,enabled)
                SELECT 'coordinate-restricted','坐标受限测试员',work_unit_code,true
                FROM platform.security_user WHERE subject_id='production-tester'
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES('coordinate-restricted','BUSINESS_OPERATOR')
                """).update();
        jdbc.sql("""
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES('coordinate-restricted','230202')
                """).update();
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "coordinate-restricted")
                        .header("Idempotency-Key", "out-of-scope-coordinate-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(JAGDAQI, "124.5000000", "50.5000000",
                                "124.5200000", "50.5200000")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    @Test
    void rejectsAStaleVersionAndAFutureCollectionTime() throws Exception {
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "stale-coordinate-version-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("123.5201", "47.9301", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_CORRECTION_STALE"));
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "future-coordinate-time-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("123.5201", "47.9301", 0)
                                .replace("2026-08-29T02:00:00Z", "2099-08-29T02:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SAMPLE_POINT_COORDINATE"));
    }

    @Test
    void replaysTheSameRequestAndRejectsReusingItsIdempotencyKeyForDifferentCoordinates()
            throws Exception {
        String first = mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "formal-coordinate-replay-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("123.5201", "47.9301", 0)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = first.replaceFirst(
                "(?s).*?\\\"requestId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "formal-coordinate-replay-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("123.5201", "47.9301", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.requestId").value(firstId));

        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "formal-coordinate-replay-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("123.5202", "47.9302", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("SAMPLE_POINT_CORRECTION_IDEMPOTENCY_CONFLICT"));
    }

    private void submitInvalid(String longitude, String latitude, String code) throws Exception {
        mockMvc.perform(post("/api/v1/sample-point-coordinate-corrections/requests")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", "invalid-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(longitude, latitude, 0)))
                .andExpect(code.equals("INVALID_SAMPLE_POINT_COORDINATE")
                        || code.equals("SAMPLE_POINT_COORDINATE_PLACEHOLDER")
                        ? status().isBadRequest() : status().isConflict())
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private String validRequest(String longitude, String latitude, long version) {
        return request(SAMPLE, "123.5100000", "47.9200000", longitude, latitude, version);
    }

    private String request(
            UUID samplePointId, String originalLongitude, String originalLatitude,
            String longitude, String latitude) {
        return request(samplePointId, originalLongitude, originalLatitude, longitude, latitude, 0);
    }

    private String request(
            UUID samplePointId, String originalLongitude, String originalLatitude,
            String longitude, String latitude, long version) {
        return """
                {"samplePointId":"%s","expectedVersion":%d,
                 "originalLongitude":"%s","originalLatitude":"%s",
                 "correctedLongitude":"%s","correctedLatitude":"%s",
                 "coordinateSource":"FIELD_GPS","coordinateCollectedAt":"2026-08-29T02:00:00Z",
                 "verifiedAddress":"龙沙区测试村一组","changeReason":"现场复核发现原定位偏移",
                 "evidenceReference":"现场照片20260829-01"}
                """.formatted(samplePointId, version, originalLongitude, originalLatitude,
                        longitude, latitude);
    }

    private void insertPoint(UUID id, String name, String longitude, String latitude) {
        insertPoint(id, name, longitude, latitude, "230202");
    }

    private void insertPoint(
            UUID id, String name, String longitude, String latitude, String regionCode) {
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE',:name,:regionCode,'APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(CAST(:longitude AS numeric),CAST(:latitude AS numeric)),4326),
                  DATE '2026-01-01',0,'production-tester','production-tester')
                """).param("id", id).param("name", name).param("regionCode", regionCode)
                .param("longitude", longitude).param("latitude", latitude).update();
    }

    private void insertBoundary(
            String regionCode, String first, String second, String third, String fourth) {
        String polygon = "POLYGON((" + first + "," + second + "," + third + ","
                + fourth + "," + first + "))";
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES(:regionCode,ST_Multi(ST_GeomFromText(:polygon,4326)),
                  'multi-region coordinate fixture','urn:test:multi-region-coordinate','test-v1',
                  'Test fixture',:regionCode,DATE '2026-08-20',repeat('9',64))
                ON CONFLICT(region_code) DO UPDATE SET geometry=excluded.geometry,
                  source_name=excluded.source_name,source_url=excluded.source_url,
                  source_revision=excluded.source_revision,source_license=excluded.source_license,
                  source_feature_id=excluded.source_feature_id,
                  source_effective_on=excluded.source_effective_on,
                  geometry_sha256=excluded.geometry_sha256
                """).param("regionCode", regionCode).param("polygon", polygon).update();
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
}
