package com.cofco.qiqihar.graintrade.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@UsesProtectedTestDatabase
class LocalSecurityBootstrapOverviewIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired SecurityPrincipalRepository principals;
    @Autowired SeparationOfDutiesPolicy separationOfDuties;
    @Autowired @Qualifier("localSecurityBootstrap") ApplicationRunner localSecurityBootstrap;

    @BeforeEach
    void restoreLocalBootstrapFixture() throws Exception {
        localSecurityBootstrap.run(new DefaultApplicationArguments(new String[0]));
    }

    @AfterEach
    void removeLocalBootstrapFixture() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE platform.business_audit_event").update();
        jdbc.sql("DELETE FROM overview.monitoring_scope_boundary_render WHERE scope_code='FORMAL_BUSINESS'").update();
        jdbc.sql("DELETE FROM overview.monitoring_scope_boundary WHERE scope_code='FORMAL_BUSINESS'").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary_render WHERE source_name='linked scope test fixture'").update();
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE source_name='linked scope test fixture'").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id = 'position-free-employee'").update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id = 'position-free-employee'").update();
        jdbc.sql("DELETE FROM platform.security_user_position WHERE subject_id = 'position-free-employee'").update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id = 'position-free-employee'").update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("""
                DELETE FROM platform.work_unit_region_scope
                WHERE work_unit_code IN (
                  'LOCAL_DEV','QIQIHAR_BUSINESS','NEHE_DEPOT','KESHAN_DEPOT',
                  'KEDONG_DEPOT','LONGZHEN_DEPOT','CHENGJISIHAN_DEPOT')
                """).update();
        jdbc.sql("""
                DELETE FROM platform.work_unit
                WHERE code IN (
                  'QIQIHAR_BUSINESS','NEHE_DEPOT','KESHAN_DEPOT',
                  'KEDONG_DEPOT','LONGZHEN_DEPOT','CHENGJISIHAN_DEPOT')
                """).update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code = 'LOCAL_DEV'").update();
        jdbc.sql("UPDATE platform.work_unit SET active = true WHERE code = 'DATABASE_AUTOMATION'").update();
        GovernedMasterDataFixtures.deleteRegions(jdbc, java.util.List.of("230281997"));
    }

    @Test
    void localIdentityAssignmentOptionsExposeTheSixFormalWorkUnitsOnly() throws Exception {
        localSecurityBootstrap.run(new DefaultApplicationArguments(new String[0]));

        mockMvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .header("X-Actor", "wang-yang")
                        .queryParam("workUnitCode", "QIQIHAR_BUSINESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnits.length()").value(6))
                .andExpect(jsonPath("$.data.workUnits[*].code").value(contains(
                        "QIQIHAR_BUSINESS", "NEHE_DEPOT", "KESHAN_DEPOT",
                        "KEDONG_DEPOT", "LONGZHEN_DEPOT", "CHENGJISIHAN_DEPOT")))
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'QIQIHAR_BUSINESS' && @.name == '齐齐哈尔经营部')]").exists())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'NEHE_DEPOT' && @.name == '讷河库')]").exists())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'KESHAN_DEPOT' && @.name == '克山库')]").exists())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'KEDONG_DEPOT' && @.name == '克东库')]").exists())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'LONGZHEN_DEPOT' && @.name == '龙镇库')]").exists())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'CHENGJISIHAN_DEPOT' && @.name == '成吉思汗库')]").exists())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'LOCAL_DEV')]").doesNotExist())
                .andExpect(jsonPath("$.data.workUnits[?(@.code == 'DATABASE_AUTOMATION')]").doesNotExist());

        assertThat(principals.findEnabled("database-master-data-automation")).isPresent();
        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT work_unit_code || ':' || region_code
                FROM platform.work_unit_region_scope
                WHERE work_unit_code IN (
                  'QIQIHAR_BUSINESS','NEHE_DEPOT','KESHAN_DEPOT',
                  'KEDONG_DEPOT','LONGZHEN_DEPOT','CHENGJISIHAN_DEPOT')
                ORDER BY work_unit_code,region_code
                """).query(String.class).list()).containsExactly(
                        "CHENGJISIHAN_DEPOT:150783",
                        "KEDONG_DEPOT:230230",
                        "KESHAN_DEPOT:230229",
                        "LONGZHEN_DEPOT:231182",
                        "NEHE_DEPOT:230281",
                        "QIQIHAR_BUSINESS:150700",
                        "QIQIHAR_BUSINESS:230200",
                        "QIQIHAR_BUSINESS:231100",
                        "QIQIHAR_BUSINESS:232700");

        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT child.code || ':' || child.name || ':' || child.administrative_level || ':'
                       || child.parent_code || ':' || parent.name
                FROM platform.region child
                JOIN platform.region parent ON parent.code=child.parent_code
                WHERE child.code='232761'
                """).query(String.class).single())
                .isEqualTo("232761:加格达奇区:COUNTY:232700:大兴安岭地区");

        assertThat(JdbcClient.create(dataSource).sql("""
                SELECT boundary.source_feature_id || ':' || boundary.source_license
                FROM overview.administrative_boundary boundary
                WHERE boundary.region_code='232761'
                  AND ST_IsValid(boundary.geometry)
                  AND NOT ST_IsEmpty(boundary.geometry)
                  AND ST_XMin(Box3D(boundary.geometry)) BETWEEN 123.75 AND 123.77
                  AND ST_XMax(Box3D(boundary.geometry)) BETWEEN 124.43 AND 124.44
                  AND ST_YMin(Box3D(boundary.geometry)) BETWEEN 50.16 AND 50.17
                  AND ST_YMax(Box3D(boundary.geometry)) BETWEEN 50.57 AND 50.58
                """).query(String.class).single())
                .isEqualTo("4376a992-05c5-482d-8394-a95f46162133:ODbL-1.0");

        mockMvc.perform(get("/api/v1/overview/regions")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '232700' && @.name == '大兴安岭地区')]").exists())
                .andExpect(jsonPath("$.data[?(@.code == '232700')].parentCode").value(hasItem(nullValue())))
                .andExpect(jsonPath("$.data[?(@.code == '232700')].boundaryGeoJson").isNotEmpty());

        JdbcClient.create(dataSource).sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,geometry_sha256
                )
                SELECT fixture.region_code,
                       ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(fixture.longitude,fixture.latitude),4326),0.04)),
                       'linked scope test fixture','https://example.invalid/linked-scope',
                       'test','test',repeat(fixture.hash_character,64)
                  FROM (VALUES
                    ('230200',123.9,47.3,'1'),
                    ('231100',127.5,49.2,'2'),
                    ('150700',119.8,49.2,'3')
                  ) fixture(region_code,longitude,latitude,hash_character)
                ON CONFLICT(region_code) DO NOTHING
                """).update();
        JdbcClient.create(dataSource).sql("SELECT overview.refresh_administrative_boundary_render()")
                .query(Object.class).single();
        JdbcClient.create(dataSource).sql("SELECT overview.refresh_monitoring_scope_boundary('FORMAL_BUSINESS')")
                .query(Object.class).single();
        JdbcClient.create(dataSource).sql("SELECT overview.refresh_monitoring_scope_boundary_render('FORMAL_BUSINESS')")
                .query(Object.class).single();

        mockMvc.perform(get("/api/v1/overview/map-scope")
                        .header("X-Actor", "wang-yang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.componentGeometryFingerprint",
                        containsString("232700:")));
    }

    @Test
    void localAdministratorInvitesAnEmployeeWithoutAnObsoletePosition() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        GovernedMasterDataFixtures.insertRegion(
                jdbc, "230281997", "无岗位验收乡镇", "230281", "TOWNSHIP", 9997);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/identity/employees")
                        .header("X-Actor", "wang-yang")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"position-free-employee","displayName":"OIDC普通员工验收",
                                 "workUnitCode":"NEHE_DEPOT","positionCodes":[],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["230281997"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.positions.length()").value(0))
                .andExpect(jsonPath("$.data.workUnitName").value("讷河库"))
                .andExpect(jsonPath("$.data.roles[0].name").value("填报员"));
    }

    @Test
    void onlyTheWuYutongAccountOwnerMayReviewItsOwnSubmission() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO platform.business_audit_event(
                  event_id,aggregate_type,aggregate_id,action_code,actor_subject_id,
                  work_unit_code,occurred_at,detail)
                VALUES(gen_random_uuid(),'MARKET_RECORD','owner-record',
                  'MARKET_RECORD_SUBMITTED','wang-yang','LOCAL_DEV',now(),'{}')
                """).update();

        var owner = principals.findEnabled("wang-yang").orElseThrow();
        assertThat(owner.displayName()).isEqualTo("吴雨桐");
        assertThat(owner.roleCodes()).contains("ACCOUNT_OWNER");
        assertThat(owner.permissionCodes()).contains("BUSINESS_SELF_APPROVE");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.security_user_role
                WHERE role_code='ACCOUNT_OWNER'
                """).query(Long.class).single()).isOne();
        assertThat(separationOfDuties.canApprove(
                "MARKET_RECORD", "owner-record", "MARKET_RECORD_SUBMITTED", owner)).isTrue();
        assertThat(separationOfDuties.canReturn(
                "MARKET_RECORD", "owner-record", "MARKET_RECORD_SUBMITTED", owner)).isTrue();
        assertThatCode(() -> separationOfDuties.requireIndependentReturner(
                "MARKET_RECORD", "owner-record", "MARKET_RECORD_SUBMITTED", owner))
                .doesNotThrowAnyException();
    }

    @Test
    void localHeadquartersActorReadsEveryFormalBusinessRootRegion() throws Exception {
        mockMvc.perform(get("/api/v1/session/me").header("X-Actor", "wang-yang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("吴雨桐"))
                .andExpect(jsonPath("$.data.workUnitName").value("齐齐哈尔经营部"))
                .andExpect(jsonPath("$.data.roleCodes").value(hasItems("BUSINESS_REVIEWER")))
                .andExpect(jsonPath("$.data.permissions").value(hasItems("BUSINESS_SELF_APPROVE")))
                .andExpect(jsonPath("$.data.regionScopes[*].code").value(
                        hasItems("230200", "231100", "150700", "232700")));

        mockMvc.perform(get("/api/v1/overview/regions")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(org.hamcrest.Matchers.contains(
                        "230200", "231100", "150700", "232700")));

        assertIncludes("230200", "230208");
        assertIncludes("231100", "231102");
        assertIncludes("150700", "150721");
        assertIncludes("232700", "232761");
    }

    @Test
    void daxinganlingRegionAndItsCountyLevelChildrenShareOneLiveScopeContract() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(jdbc.sql("""
                SELECT code || ':' || name
                FROM platform.region
                WHERE parent_code='232700' AND administrative_level='COUNTY'
                ORDER BY sort_order,code
                """).query(String.class).list()).containsExactly(
                        "232761:加格达奇区",
                        "232762:松岭区",
                        "232763:新林区",
                        "232764:呼中区",
                        "232701:漠河市",
                        "232721:呼玛县",
                        "232722:塔河县");

        assertThat(jdbc.sql("""
                SELECT count(*)
                FROM overview.administrative_boundary boundary
                WHERE boundary.region_code IN (
                  '232700','232701','232721','232722','232761','232762','232763','232764')
                  AND boundary.source_feature_id IS NOT NULL
                  AND boundary.source_license='ODbL-1.0'
                  AND ST_IsValid(boundary.geometry)
                  AND NOT ST_IsEmpty(boundary.geometry)
                """).query(Long.class).single()).isEqualTo(8L);

        assertThat(jdbc.sql("""
                WITH county_coverage AS (
                  SELECT child.parent_code,
                         ST_UnaryUnion(ST_Collect(render.geometry)) geometry
                  FROM platform.region child
                  JOIN overview.administrative_boundary_render render
                    ON render.region_code=child.code
                  WHERE child.administrative_level='COUNTY'
                    AND child.parent_code='232700'
                  GROUP BY child.parent_code
                )
                SELECT count(*)
                FROM county_coverage coverage
                JOIN overview.administrative_boundary_render parent
                  ON parent.region_code=coverage.parent_code
                WHERE overview.has_visible_surface_gap(
                        ST_Difference(parent.geometry,coverage.geometry))
                   OR overview.has_visible_surface_gap(
                        ST_Difference(coverage.geometry,parent.geometry))
                """).query(Long.class).single()).isZero();

        mockMvc.perform(get("/api/v1/overview/regions")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '232700' && @.name == '大兴安岭地区')]").exists())
                .andExpect(jsonPath("$.data[?(@.code == '232700')].parentCode").value(hasItem(nullValue())));

        mockMvc.perform(get("/api/v1/overview/regions")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("parentCode", "232700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(contains(
                        "232761", "232762", "232763", "232764",
                        "232701", "232721", "232722")))
                .andExpect(jsonPath("$.data[*].parentCode").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("232700"))))
                .andExpect(jsonPath("$.data[*].boundaryGeoJson").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.notNullValue())));

        mockMvc.perform(get("/api/v1/overview/dashboard")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.prefectureCount").value(4))
                .andExpect(jsonPath("$.data.scope.countyCount").value(27));
    }

    private void assertIncludes(String parentCode, String childCode) throws Exception {
        mockMvc.perform(get("/api/v1/overview/regions")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026")
                        .queryParam("parentCode", parentCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(org.hamcrest.Matchers.hasItem(childCode)));
    }

}
