package com.cofco.qiqihar.graintrade.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @AfterEach
    void removeLocalBootstrapFixture() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.security_user WHERE subject_id = 'wang-yang'").update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code = 'LOCAL_DEV'").update();
        jdbc.sql("DELETE FROM platform.work_unit WHERE code = 'LOCAL_DEV'").update();
    }

    @Test
    void localHeadquartersActorReadsEveryFormalBusinessRootRegion() throws Exception {
        mockMvc.perform(get("/api/v1/session/me").header("X-Actor", "wang-yang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workUnitName").value("平台运营管理部"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("SYSTEM_ADMIN"));

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
