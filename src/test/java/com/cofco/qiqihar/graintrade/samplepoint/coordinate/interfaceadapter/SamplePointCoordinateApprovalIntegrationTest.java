package com.cofco.qiqihar.graintrade.samplepoint.coordinate.interfaceadapter;

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
class SamplePointCoordinateApprovalIntegrationTest {
    private static final UUID OCCUPIED_POINT =
            UUID.fromString("95000000-0000-0000-0000-000000000011");

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256)
                VALUES('230202',
                  ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(123.9182,47.3543),4326),0.01)),
                  'coordinate approval fixture','urn:test:sample-point-coordinate-approval','test-v1',
                  'Test fixture','230202',DATE '2026-08-20',repeat('7',64))
                ON CONFLICT(region_code) DO UPDATE SET
                  geometry=excluded.geometry,source_name=excluded.source_name,
                  source_url=excluded.source_url,source_revision=excluded.source_revision,
                  source_license=excluded.source_license,source_feature_id=excluded.source_feature_id,
                  source_effective_on=excluded.source_effective_on,
                  geometry_sha256=excluded.geometry_sha256
                """).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,location_state,
                  governed_point,effective_from,version,created_by,updated_by)
                VALUES(:id,'SURVEY_SITE','已占用坐标样本点','230202','APPROVED','VALID',
                  ST_SetSRID(ST_MakePoint(123.9182,47.3543),4326),DATE '2026-01-01',0,
                  'production-tester','production-tester')
                """).param("id", OCCUPIED_POINT).update();
    }

    @Test
    void productionApprovalRejectsADifferentSamplePointAtAnOccupiedCoordinate() throws Exception {
        UUID photo = stagePhoto("production-tester", "production-coordinate.png");
        String id = createProduction(photo);
        submitProduction(id);

        mockMvc.perform(post("/api/v1/production-records/{id}/approve", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_COORDINATE_OCCUPIED"));
    }

    @Test
    void marketApprovalRejectsADifferentSamplePointAtAnOccupiedCoordinate() throws Exception {
        UUID photo = stagePhoto("market-tester", "market-coordinate.png");
        String id = createMarket(photo);
        mockMvc.perform(post("/api/v1/market-records/{id}/submit", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/market-records/{id}/approve", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_POINT_COORDINATE_OCCUPIED"));
    }

    private String createProduction(UUID photo) throws Exception {
        String body = """
                {"productCode":"CORN","objectTypeCode":"FARMER","regionCode":"230202",
                 "surveyDate":"2026-08-01","cultivatedAreaMu":"1","yieldPerMuKilograms":"2",
                 "quality":{"MOISTURE":"14"},"costs":{},"insurance":{},"subsidies":{},
                 "submissionMetadata":{"PROD_REPORTER_NAME":"测试填报员","PROD_SURVEYOR_NAME":"王雷",
                   "PROD_SURVEYOR_PHONE":"13800000000","PROD_SAMPLE_NAME":"坐标冲突生产样本点",
                   "PROD_SAMPLE_CONTACT":"13911110001","PROD_SAMPLE_LATITUDE":"47.354300",
                   "PROD_SAMPLE_LONGITUDE":"123.918200"},"evidencePhotoIds":["%s"]}
                """.formatted(photo);
        return mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void submitProduction(String id) throws Exception {
        mockMvc.perform(post("/api/v1/production-records/{id}/submit", id)
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());
    }

    private String createMarket(UUID photo) throws Exception {
        String body = """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"TRADER","MKT_REGION":"230200","MKT_TRADE_DATE":"2026-08-01",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":"2300",
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK",
                 "MKT_REPORTER_NAME":"测试填报员","MKT_SURVEYOR_NAME":"王雷",
                 "MKT_SURVEYOR_PHONE":"13800000000","MKT_SAMPLE_NAME":"坐标冲突市场样本点",
                 "MKT_SAMPLE_CONTACT":"13911110002","MKT_SAMPLE_LATITUDE":"47.3543000",
                 "MKT_SAMPLE_LONGITUDE":"123.9182000"},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"},
                 "evidencePhotoIds":["%s"]}
                """.formatted(photo);
        return mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private UUID stagePhoto(String actor, String filename) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence.evidence_photo(photo_id,state_code,original_filename,media_type,
                  original_bytes,watermarked_bytes,byte_length,sha256,captured_at,capture_latitude,
                  capture_longitude,watermark_text,uploaded_by,uploaded_at)
                VALUES(:id,'STAGED',:filename,'image/png',decode('00','hex'),decode('01','hex'),
                  1,encode(sha256(decode('00','hex')),'hex'),now(),47.3543,123.9182,
                  '坐标校验测试水印',:actor,now())
                """).param("id", id).param("filename", filename).param("actor", actor).update();
        return id;
    }
}
