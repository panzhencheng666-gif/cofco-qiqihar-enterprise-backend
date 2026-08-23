package com.cofco.qiqihar.graintrade.samplepoint.network.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
class AnnualSampleNetworkRestIntegrationTest {
    private static final String OPERATOR = "annual-network-operator";
    private static final String REVIEWER = "annual-network-reviewer";
    private static final String WORK_UNIT = "ANNUAL_NETWORK_TEST";
    private static final String PREFECTURE = "230200";
    private static final String TOWNSHIP = "230202995";
    private static final String VILLAGE_ONE = "230202995001";
    private static final String VILLAGE_TWO = "230202995002";
    private static final String OUTSIDE_COUNTY = "231199";
    private static final String OUTSIDE_TOWNSHIP = "231199995";
    private static final String OUTSIDE_VILLAGE = "231199995001";
    private static final String VILLAGE_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000002";
    private static final String TOWNSHIP_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000003";
    private static final String COUNTY_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000004";
    private static final String PREFECTURE_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000005";
    private static final String OUTSIDE_SAMPLE_POINT =
            "13300000-0000-0000-0000-000000000006";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        cleanOperationalRows();
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "年度网络接口测试乡", "230202", "TOWNSHIP", 995);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, VILLAGE_ONE, "年度网络接口测试一村", TOWNSHIP, "VILLAGE", 1);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, VILLAGE_TWO, "年度网络接口测试二村", TOWNSHIP, "VILLAGE", 2);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_COUNTY, "年度网络接口测试外辖区县", "231100", "COUNTY", 995);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_TOWNSHIP, "年度网络接口测试外辖乡", OUTSIDE_COUNTY,
                "TOWNSHIP", 995);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, OUTSIDE_VILLAGE, "年度网络接口测试外辖村", OUTSIDE_TOWNSHIP,
                "VILLAGE", 1);
        jdbc.sql("""
                INSERT INTO platform.geography_import_batch(
                  dataset_sha256,source_workbook_sha256,source_revision,
                  township_count,village_count,coordinate_count)
                VALUES(repeat('c',64),repeat('d',64),'annual-network-rest-test',1,2,2)
                ON CONFLICT(dataset_sha256) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO platform.region_location(
                  region_code,original_coordinate,wgs84_coordinate,original_crs,target_crs,
                  conversion_method,source_name,source_url,source_revision,place_type,matched_by,
                  match_confidence,review_status,dataset_sha256)
                VALUES
                  (:one,ST_SetSRID(ST_MakePoint(123.80,47.20),4490),
                    ST_SetSRID(ST_MakePoint(123.80,47.20),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/annual-network',
                    'annual-network-rest-test','行政村','exact test match','HIGH','REVIEWED',repeat('c',64)),
                  (:two,ST_SetSRID(ST_MakePoint(123.81,47.21),4490),
                    ST_SetSRID(ST_MakePoint(123.81,47.21),4326),'EPSG:4490','EPSG:4326',
                    'test transform','test source','https://example.invalid/annual-network',
                    'annual-network-rest-test','行政村','exact test match','HIGH','REVIEWED',repeat('c',64))
                """).param("one", VILLAGE_ONE).param("two", VILLAGE_TWO).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:unit,'年度样本网络测试单位',9935)
                ON CONFLICT(code) DO NOTHING;
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,:prefecture) ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:operator,'年度网络填报员',:unit),(:reviewer,'年度网络管理员',:unit)
                ON CONFLICT(subject_id) DO NOTHING;
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:operator,'BUSINESS_OPERATOR'),(:reviewer,'BUSINESS_REVIEWER')
                ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:operator,:prefecture),(:reviewer,:prefecture) ON CONFLICT DO NOTHING
                """).param("unit", WORK_UNIT).param("operator", OPERATOR)
                .param("reviewer", REVIEWER).param("prefecture", PREFECTURE).update();
        jdbc.sql("""
                INSERT INTO registry.sample_point(
                  sample_point_id,kind_code,canonical_name,region_code,approval_state,
                  location_state,effective_from,created_by,updated_by)
                VALUES
                  (CAST(:villageId AS uuid),'SURVEY_SITE','年度网络村级真实样本点',:village,
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:townshipId AS uuid),'SURVEY_SITE','年度网络乡镇级真实样本点',:township,
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:countyId AS uuid),'SURVEY_SITE','年度网络区县级真实样本点','230202',
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:prefectureId AS uuid),'SURVEY_SITE','年度网络地市级真实样本点',:prefecture,
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator),
                  (CAST(:outsideId AS uuid),'SURVEY_SITE','年度网络外辖真实样本点','231100',
                    'APPROVED','MISSING',DATE '2026-01-01',:operator,:operator)
                """).param("villageId", VILLAGE_SAMPLE_POINT)
                .param("townshipId", TOWNSHIP_SAMPLE_POINT)
                .param("countyId", COUNTY_SAMPLE_POINT)
                .param("prefectureId", PREFECTURE_SAMPLE_POINT)
                .param("outsideId", OUTSIDE_SAMPLE_POINT).param("village", VILLAGE_ONE)
                .param("township", TOWNSHIP)
                .param("prefecture", PREFECTURE)
                .param("operator", OPERATOR).update();
    }

    @AfterEach
    void tearDown() {
        cleanOperationalRows();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id IN (:subjects)")
                .param("subjects", List.of(OPERATOR, REVIEWER)).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit")
                .param("unit", WORK_UNIT).update();
        GovernedMasterDataFixtures.deleteRegions(
                jdbc, List.of(VILLAGE_ONE, VILLAGE_TWO, TOWNSHIP,
                        OUTSIDE_VILLAGE, OUTSIDE_TOWNSHIP, OUTSIDE_COUNTY));
    }

    @Test
    void exposesDesignReferencesAndPublishesAnIndependentlyReviewedAnnualNetwork() throws Exception {
        mvc.perform(get("/api/v1/sample-networks/design-points")
                        .principal(() -> OPERATOR).queryParam("regionCode", "230202"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].coordinateSourceRevision")
                        .value("annual-network-rest-test"));

        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("DRAFT"))
                .andExpect(jsonPath("$.data.memberships.length()").value(0));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","relationType":"EXACT_VILLAGE",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年真实在网样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberships[0].samplePointId")
                        .value(VILLAGE_SAMPLE_POINT))
                .andExpect(jsonPath("$.data.memberships[0].statusCode").value("ACTIVE"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"2026年二村代表关系核验材料",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年乡镇级真实在网样本","version":0}
                                """.formatted(VILLAGE_TWO)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年区县级真实在网样本","version":0}
                                """))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, PREFECTURE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2026年地市级真实在网样本","version":0}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", VILLAGE_TWO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designPointCount").value(1))
                .andExpect(jsonPath("$.data.actualPoints[?(@.samplePointId=='%s')]"
                        .formatted(TOWNSHIP_SAMPLE_POINT)).exists())
                .andExpect(jsonPath("$.data.actualPoints[?(@.samplePointId=='%s')]"
                        .formatted(COUNTY_SAMPLE_POINT)).exists())
                .andExpect(jsonPath("$.data.actualPoints[?(@.samplePointId=='%s')]"
                        .formatted(PREFECTURE_SAMPLE_POINT)).exists())
                .andExpect(jsonPath("$.data.relations[?(@.samplePointId=='%s' && "
                        .formatted(TOWNSHIP_SAMPLE_POINT)
                        + "@.relationType=='EXPLICIT_REPRESENTATION')]"
                        + ".reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.relations[?(@.samplePointId=='%s' && "
                        .formatted(TOWNSHIP_SAMPLE_POINT)
                        + "@.relationType=='REGIONAL_ASSOCIATION')]").exists());

        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2026)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(post("/api/v1/sample-networks/{year}/review", 2026)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"decision\":\"APPROVE\",\"reason\":\"名单核验通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCode").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(2));

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", "230202"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.designPointCount").value(2))
                .andExpect(jsonPath("$.data.activeSamplePointCount").value(4))
                .andExpect(jsonPath("$.data.actualLevelCounts.prefecture").value(1))
                .andExpect(jsonPath("$.data.actualLevelCounts.township").value(1))
                .andExpect(jsonPath("$.data.actualLevelCounts.county").value(1))
                .andExpect(jsonPath("$.data.actualLevelCounts.village").value(1))
                .andExpect(jsonPath("$.data.exactCoveredDesignPointCount").value(1))
                .andExpect(jsonPath("$.data.representedDesignPointCount").value(1))
                .andExpect(jsonPath("$.data.regionalAssociationDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.unrelatedDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.designPoints.length()").value(2))
                .andExpect(jsonPath("$.data.actualPoints.length()").value(4))
                .andExpect(jsonPath("$.data.actualPoints[?(@.locatedRegionLevel=='PREFECTURE')]")
                        .exists())
                .andExpect(jsonPath("$.data.actualPoints[?(@.locatedRegionLevel=='COUNTY')]")
                        .exists())
                .andExpect(jsonPath("$.data.actualPoints[?(@.locatedRegionLevel=='TOWNSHIP')]")
                        .exists())
                .andExpect(jsonPath("$.data.actualPoints[?(@.locatedRegionLevel=='VILLAGE')]")
                        .exists())
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='REGIONAL_ASSOCIATION')]").exists())
                .andExpect(jsonPath("$.data.relations[?(@.relationType=='EXACT_VILLAGE')]")
                        .exists())
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='EXPLICIT_REPRESENTATION')]"
                                + ".reviewStatus")
                        .value("APPROVED"))
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='EXPLICIT_REPRESENTATION')]"
                                + ".reviewedBy")
                        .value(REVIEWER));

        mvc.perform(post("/api/v1/sample-networks/{year}", 2027)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carriedFromYear\":2026}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value("DRAFT"))
                .andExpect(jsonPath("$.data.carriedFromYear").value(2026))
                .andExpect(jsonPath("$.data.memberships.length()").value(4))
                .andExpect(jsonPath("$.data.memberships[?(@.samplePointId=='%s')]"
                        .formatted(PREFECTURE_SAMPLE_POINT) + ".statusCode").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.memberships[0].sourceCode").value("CARRIED_FORWARD"))
                .andExpect(jsonPath("$.data.memberships[1].sourceCode").value("CARRIED_FORWARD"))
                .andExpect(jsonPath("$.data.memberships[2].sourceCode").value("CARRIED_FORWARD"))
                .andExpect(jsonPath("$.data.memberships[3].sourceCode").value("CARRIED_FORWARD"));

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2027)
                        .principal(() -> OPERATOR).queryParam("regionCode", "230202"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualPoints.length()").value(4))
                .andExpect(jsonPath("$.data.relations[?(@.relationType=='EXACT_VILLAGE')]"
                        + ".reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath(
                        "$.data.relations[?(@.relationType=='EXPLICIT_REPRESENTATION')]"
                                + ".reviewStatus")
                        .value("PENDING_REVIEW"));

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM production.production_record WHERE survey_year=2027)
                     + (SELECT count(*) FROM market.market_record WHERE survey_year=2027)
                """).query(Long.class).single()).isZero();
    }

    @Test
    void preventsTheAnnualNetworkSubmitterFromApprovingTheirOwnList() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2028)
                        .principal(() -> REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2028, VILLAGE_SAMPLE_POINT).principal(() -> REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","relationType":"EXACT_VILLAGE",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"2028年真实在网样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2028)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/sample-networks/{year}/review", 2028)
                        .principal(() -> REVIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"decision\":\"APPROVE\",\"reason\":\"自审不允许\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void rejectsRelationsThatAreIncompleteOrClaimAnExactMatchOutsideTheMemberVillage()
            throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXACT_VILLAGE","statusCode":"ACTIVE",
                                 "sourceCode":"NEW","reason":"错误跨村精确关系","version":0}
                                """.formatted(VILLAGE_TWO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","statusCode":"ACTIVE",
                                 "sourceCode":"NEW","reason":"缺少关系类型","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"缺少代表关系依据","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership WHERE network_year=2026
                """).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsOutOfScopeActualAndDesignRegionsBeforeWritingMemberships() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, OUTSIDE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"外辖样本不得写入",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"外辖样本","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"外辖设计村不得写入",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"外辖设计关系","version":0}
                                """.formatted(OUTSIDE_VILLAGE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM registry.sample_network_membership WHERE network_year=2026
                """).query(Long.class).single()).isZero();
    }

    @Test
    void keepsVillageMembersWithoutReviewedRelationsOutOfRegionalAssociations() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, VILLAGE_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"未建立设计关系的村级真实点","version":0}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/sample-networks/{year}/comparison", 2026)
                        .principal(() -> OPERATOR).queryParam("regionCode", VILLAGE_ONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualPoints[?(@.samplePointId=='%s')]"
                        .formatted(VILLAGE_SAMPLE_POINT)).exists())
                .andExpect(jsonPath("$.data.relations[?(@.samplePointId=='%s' && "
                        .formatted(VILLAGE_SAMPLE_POINT)
                        + "@.relationType=='REGIONAL_ASSOCIATION')]").doesNotExist())
                .andExpect(jsonPath("$.data.regionalAssociationDesignPointCount").value(0))
                .andExpect(jsonPath("$.data.unrelatedDesignPointCount").value(1));
    }

    @Test
    void rejectsNullMemberFieldsAndLeavesNoPartialRowsAfterTheDraftCloses() throws Exception {
        mvc.perform(post("/api/v1/sample-networks/{year}", 2026)
                        .principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":null,"sourceCode":"NEW",
                                 "reason":"空状态","version":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_MEMBER_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s","relationType":null,
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"空关系类型","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_RELATION_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"触发非村级设计关系约束",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"验证关系失败整体回滚","version":0}
                                """.formatted(TOWNSHIP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM registry.sample_network_membership
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                     + (SELECT count(*) FROM registry.sample_network_design_relation
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                """).param("sample", TOWNSHIP_SAMPLE_POINT).query(Long.class).single()).isZero();

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":null,
                                 "reason":"空来源","version":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_MEMBER_INVALID"));

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, COUNTY_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"先建立可提交成员","version":0}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sample-networks/{year}/submit", 2026)
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/sample-networks/{year}/members/{samplePointId}",
                        2026, TOWNSHIP_SAMPLE_POINT).principal(() -> OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"designVillageRegionCode":"%s",
                                 "relationType":"EXPLICIT_REPRESENTATION",
                                 "evidenceReference":"关闭草稿后的关系",
                                 "statusCode":"ACTIVE","sourceCode":"NEW",
                                 "reason":"关闭后不得写入","version":0}
                                """.formatted(VILLAGE_ONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_NETWORK_NOT_EDITABLE"));

        assertThat(jdbc.sql("""
                SELECT (SELECT count(*) FROM registry.sample_network_membership
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                     + (SELECT count(*) FROM registry.sample_network_design_relation
                        WHERE network_year=2026 AND sample_point_id=CAST(:sample AS uuid))
                """).param("sample", TOWNSHIP_SAMPLE_POINT).query(Long.class).single()).isZero();
    }

    private void cleanOperationalRows() {
        jdbc.sql("DELETE FROM registry.sample_network_design_relation "
                + "WHERE network_year IN (2026,2027,2028)").update();
        jdbc.sql("DELETE FROM registry.sample_network_membership WHERE network_year IN (2026,2027,2028)")
                .update();
        jdbc.sql("DELETE FROM registry.sample_network_year WHERE network_year IN (2026,2027,2028)")
                .update();
        jdbc.sql("DELETE FROM registry.sample_point WHERE sample_point_id IN (:ids)")
                .param("ids", List.of(UUID.fromString(VILLAGE_SAMPLE_POINT),
                        UUID.fromString(TOWNSHIP_SAMPLE_POINT),
                        UUID.fromString(COUNTY_SAMPLE_POINT),
                        UUID.fromString(PREFECTURE_SAMPLE_POINT),
                        UUID.fromString(OUTSIDE_SAMPLE_POINT)))
                .update();
        jdbc.sql("DELETE FROM platform.region_location WHERE region_code IN (:regions)")
                .param("regions", List.of(VILLAGE_ONE, VILLAGE_TWO)).update();
        jdbc.sql("DELETE FROM platform.geography_import_batch WHERE dataset_sha256=repeat('c',64)")
                .update();
    }
}
