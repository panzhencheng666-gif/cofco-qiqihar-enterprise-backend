package com.cofco.qiqihar.graintrade.regionalproduction.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class,
        properties = "qiqihar.security.require-read-authentication=true")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class RegionalCropAnnualStatRestIntegrationTest {
    private static final String OPERATOR = "regional-annual-operator";
    private static final String READER = "regional-annual-reader";
    private static final String UNIT = "REGIONAL_ANNUAL_TEST";
    private static final String PREFECTURE = "230200";
    private static final String COUNTY = "230202";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE production.regional_crop_annual_stat_history,
                         production.regional_crop_annual_stat
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:unit,'地区年度产情测试单位',9946)
                ON CONFLICT(code) DO UPDATE SET active=true;
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,:prefecture) ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:operator,'地区产情填报员',:unit),(:reader,'地区产情只读员',:unit)
                ON CONFLICT(subject_id) DO UPDATE SET enabled=true,work_unit_code=EXCLUDED.work_unit_code;
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:operator,'BUSINESS_OPERATOR'),(:reader,'REPORTER')
                ON CONFLICT(subject_id,role_code,valid_from) DO UPDATE SET valid_until=NULL;
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:operator,:prefecture),(:reader,:prefecture)
                ON CONFLICT(subject_id,region_code,valid_from) DO UPDATE SET valid_until=NULL
                """).param("unit", UNIT).param("prefecture", PREFECTURE)
                .param("operator", OPERATOR).param("reader", READER).update();
    }

    @Test
    void savesAsFormalDataRequeriesAndEmitsAuditOutboxInOneFlow() throws Exception {
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataYear":2026,"productCode":"CORN",
                                 "plantedAreaMu":"100.0000","yieldPerMuKg":"500.0000",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOutputKg").value("50000.0000"))
                .andExpect(jsonPath("$.data.version").value(0));

        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataYear":2026,"productCode":"CORN",
                                 "plantedAreaMu":"120.0000","yieldPerMuKg":"550.0000",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOutputKg").value("66000.0000"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(get("/api/v1/production/regional-annual-stats")
                        .principal(() -> OPERATOR).queryParam("year", "2026")
                        .queryParam("productCode", "CORN")
                        .queryParam("prefectureCode", PREFECTURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].regionCode").value(COUNTY))
                .andExpect(jsonPath("$.data[0].plantedAreaMu").value("120.0000"));

        mvc.perform(get("/api/v1/overview/regional-crop-summary")
                        .principal(() -> OPERATOR).queryParam("year", "2026")
                        .queryParam("productCode", "CORN").queryParam("regionCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plantedAreaMu").value("120.0000"))
                .andExpect(jsonPath("$.data.yieldPerMuKg").value("550.0000"))
                .andExpect(jsonPath("$.data.totalOutputKg").value("66000.0000"))
                .andExpect(jsonPath("$.data.comparisonAvailable").value(false))
                .andExpect(jsonPath("$.data.comparisonMessage").value("缺少对比年度数据"));

        assertThat(jdbc.sql("SELECT count(*) FROM production.regional_crop_annual_stat_history")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='REGIONAL_CROP_ANNUAL_STAT'
                  AND aggregate_id=:aggregateId
                  AND action_code='REGIONAL_CROP_ANNUAL_STAT_UPSERTED'
                """).param("aggregateId", COUNTY + ":2026:CORN")
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='REGIONAL_CROP_ANNUAL_STAT'
                  AND aggregate_id=:aggregateId AND product_code='CORN'
                  AND region_codes::text[] @> ARRAY[:county,:prefecture]::text[]
                """).param("aggregateId", COUNTY + ":2026:CORN")
                .param("county", COUNTY).param("prefecture", PREFECTURE)
                .query(Long.class).single()).isEqualTo(2L);
        String detail = jdbc.sql("""
                SELECT detail::text
                FROM platform.business_event_outbox
                WHERE aggregate_type='REGIONAL_CROP_ANNUAL_STAT'
                  AND aggregate_id=:aggregateId
                ORDER BY event_sequence DESC LIMIT 1
                """).param("aggregateId", COUNTY + ":2026:CORN")
                .query(String.class).single();
        assertThat(detail)
                .contains("\"surveyYear\": 2026")
                .doesNotContain("\"dataYear\"");
    }

    @Test
    void savesAreaBeforeYieldAndCalculatesOutputAfterYieldIsReported() throws Exception {
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataYear":2026,"productCode":"CORN",
                                 "plantedAreaMu":"17780.0000","yieldPerMuKg":null,
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plantedAreaMu").value("17780.0000"))
                .andExpect(jsonPath("$.data.yieldPerMuKg").value((Object) null))
                .andExpect(jsonPath("$.data.totalOutputKg").value((Object) null));

        mvc.perform(get("/api/v1/overview/regional-crop-summary")
                        .principal(() -> OPERATOR).queryParam("year", "2026")
                        .queryParam("productCode", "CORN").queryParam("regionCode", COUNTY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plantedAreaMu").value("17780.0000"))
                .andExpect(jsonPath("$.data.yieldPerMuKg").value((Object) null))
                .andExpect(jsonPath("$.data.totalOutputKg").value((Object) null));

        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataYear":2026,"productCode":"CORN",
                                 "plantedAreaMu":"17780.0000","yieldPerMuKg":"500.0000",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalOutputKg").value("8890000.0000"))
                .andExpect(jsonPath("$.data.version").value(1));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM production.regional_crop_annual_stat_history
                WHERE region_code=:county AND yield_per_mu_kg IS NULL AND total_output_kg IS NULL
                """).param("county", COUNTY).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectsStaleVersionInvalidFieldsAndUnauthorizedWrites() throws Exception {
        String valid = """
                {"dataYear":2026,"productCode":"RICE","plantedAreaMu":"1.0000",
                 "yieldPerMuKg":"2.0000","expectedVersion":0}
                """;
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REGIONAL_ANNUAL_STAT_VERSION_CONFLICT"));

        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> READER).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", PREFECTURE)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REGIONAL_ANNUAL_STAT_COUNTY_REQUIRED"));
        mvc.perform(put("/api/v1/production/regional-annual-stats/{regionCode}", COUNTY)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("1.0000", "-1.0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REGIONAL_ANNUAL_STAT_VALUE_INVALID"));
    }
}
