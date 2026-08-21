package com.cofco.qiqihar.graintrade.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository;
import com.cofco.qiqihar.graintrade.shared.security.application.SeparationOfDutiesPolicy;
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
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code = 'LOCAL_DEV'").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code = 'LOCAL_DEV'").update();
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
                .andExpect(jsonPath("$.data.workUnitName").value("平台运营管理部"))
                .andExpect(jsonPath("$.data.roleCodes").value(hasItems("BUSINESS_REVIEWER")))
                .andExpect(jsonPath("$.data.permissions").value(hasItems("BUSINESS_SELF_APPROVE")))
                .andExpect(jsonPath("$.data.regionScopes[*].code").value(
                        hasItems("230200", "231100", "150700")));

        mockMvc.perform(get("/api/v1/overview/regions")
                        .header("X-Actor", "wang-yang")
                        .queryParam("productCode", "CORN")
                        .queryParam("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(org.hamcrest.Matchers.contains(
                        "230200", "231100", "150700")));

        assertIncludes("230200", "230208");
        assertIncludes("231100", "231102");
        assertIncludes("150700", "150721");
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
