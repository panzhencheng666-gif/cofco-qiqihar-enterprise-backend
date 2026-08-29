package com.cofco.qiqihar.graintrade.supplybalance.interfaceadapter;

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
class SupplyBalanceRestIntegrationTest {
    private static final String OPERATOR = "supply-balance-operator";
    private static final String READER = "supply-balance-reader";
    private static final String UNIT = "SUPPLY_BALANCE_TEST";
    private static final String PREFECTURE = "230200";
    private static final String COUNTY_ONE = "230202";
    private static final String COUNTY_TWO = "230203";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE production.supply_demand_balance_history,
                         production.supply_demand_balance,
                         production.regional_crop_annual_stat_history,
                         production.regional_crop_annual_stat
                """).update();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:unit,'供需平衡测试单位',9947) ON CONFLICT(code) DO UPDATE SET active=true;
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,:prefecture) ON CONFLICT DO NOTHING;
                INSERT INTO platform.security_user(subject_id,display_name,work_unit_code)
                VALUES(:operator,'供需平衡填报员',:unit),(:reader,'供需平衡只读员',:unit)
                ON CONFLICT(subject_id) DO UPDATE SET enabled=true,work_unit_code=EXCLUDED.work_unit_code;
                INSERT INTO platform.security_user_role(subject_id,role_code)
                VALUES(:operator,'BUSINESS_OPERATOR'),(:reader,'REPORTER')
                ON CONFLICT(subject_id,role_code,valid_from) DO UPDATE SET valid_until=NULL;
                INSERT INTO platform.security_user_region_scope(subject_id,region_code)
                VALUES(:operator,:prefecture),(:reader,:prefecture)
                ON CONFLICT(subject_id,region_code,valid_from) DO UPDATE SET valid_until=NULL
                """).param("unit", UNIT).param("prefecture", PREFECTURE)
                .param("operator", OPERATOR).param("reader", READER).update();
        jdbc.sql("""
                INSERT INTO production.regional_crop_annual_stat(
                  region_code,data_year,product_code,planted_area_mu,yield_per_mu_kg,created_by,updated_by)
                VALUES(:one,2026,'RICE',150000,100,:actor,:actor),
                      (:two,2026,'RICE',300000,100,:actor,:actor)
                """).param("one", COUNTY_ONE).param("two", COUNTY_TWO).param("actor", OPERATOR).update();
    }

    @Test
    void persistsCountyHistoryAndAggregatesCityFromRegionalProductionAndManualRows() throws Exception {
        save(COUNTY_ONE, riceValues("1", "1", "1", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.code == 'OUTPUT')].value").value("1.500000"));
        save(COUNTY_TWO, riceValues("2", "2", "0", "1")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/supply-balances").principal(() -> OPERATOR)
                        .queryParam("regionCode", PREFECTURE).queryParam("surveyYear", "2026")
                        .queryParam("productCode", "RICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.administrativeLevel").value("PREFECTURE"))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'PLANTED_AREA')].value").value("3.000000"))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'TOTAL_SUPPLY')].value").value("7.500000"))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'TOTAL_DEMAND')].value").value("5.0"))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'CLOSING_INVENTORY')].value").value("2.500000"));

        save(COUNTY_ONE, riceValues("3", "1", "1", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/api/v1/supply-balances/{region}/{year}/{product}/history",
                        COUNTY_ONE, 2026, "RICE").principal(() -> OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].manualValues.OPENING_INVENTORY").value(1.0000));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='SUPPLY_DEMAND_BALANCE' AND action_code='SUPPLY_BALANCE_UPSERTED'
                """).query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='SUPPLY_DEMAND_BALANCE' AND product_code='RICE'
                  AND region_codes::text[] @> ARRAY[:county,:prefecture]::text[]
                """).param("county", COUNTY_ONE).param("prefecture", PREFECTURE)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void exposesPartialCountyAreaAndAggregatesPrefectureWithoutInventingYieldOrOutput() throws Exception {
        jdbc.sql("""
                UPDATE production.regional_crop_annual_stat
                SET yield_per_mu_kg=NULL
                WHERE region_code=:county AND data_year=2026 AND product_code='RICE'
                """).param("county", COUNTY_TWO).update();

        mvc.perform(get("/api/v1/supply-balances").principal(() -> OPERATOR)
                        .queryParam("regionCode", COUNTY_TWO).queryParam("surveyYear", "2026")
                        .queryParam("productCode", "RICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.code == 'PLANTED_AREA')].value").value("2.000000"))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'YIELD')].value").value((Object) null))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'OUTPUT')].value").value((Object) null));

        mvc.perform(get("/api/v1/supply-balances").principal(() -> OPERATOR)
                        .queryParam("regionCode", PREFECTURE).queryParam("surveyYear", "2026")
                        .queryParam("productCode", "RICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.code == 'PLANTED_AREA')].value").value("3.000000"))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'YIELD')].value").value((Object) null))
                .andExpect(jsonPath("$.data.rows[?(@.code == 'OUTPUT')].value").value((Object) null));
    }

    @Test
    void rejectsUnknownProductFieldCityWriteStaleVersionAndReadOnlyWriter() throws Exception {
        String valid = riceValues("1", "1", "0", "0");
        save(COUNTY_ONE, valid).andExpect(status().isOk());
        save(COUNTY_ONE, valid).andExpect(status().isOk());
        save(COUNTY_ONE, valid).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUPPLY_BALANCE_VERSION_CONFLICT"));

        mvc.perform(put("/api/v1/supply-balances/{region}/{year}/{product}", COUNTY_ONE, 2026, "RICE")
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"manualValues":{"FEED_USE":"1"},"notes":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SUPPLY_BALANCE_FIELD_INVALID"));
        mvc.perform(put("/api/v1/supply-balances/{region}/{year}/{product}", PREFECTURE, 2026, "RICE")
                        .principal(() -> OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("\"version\":0", "\"version\":1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SUPPLY_BALANCE_COUNTY_REQUIRED"));
        mvc.perform(put("/api/v1/supply-balances/{region}/{year}/{product}", COUNTY_TWO, 2026, "RICE")
                        .principal(() -> READER).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_PERMISSION_DENIED"));
    }

    private org.springframework.test.web.servlet.ResultActions save(String regionCode, String body) throws Exception {
        return mvc.perform(put("/api/v1/supply-balances/{region}/{year}/{product}",
                        regionCode, 2026, "RICE").principal(() -> OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String riceValues(String opening, String food, String rail, String road) {
        return """
                {"version":0,"manualValues":{"OPENING_INVENTORY":"%s","FOOD_USE":"%s",
                 "OTHER_USE":"0","POLICY_RESERVE":"0","RAIL_OUTFLOW":"%s","ROAD_OUTFLOW":"%s"},
                 "notes":{"OPENING_INVENTORY":"本年度正式口径"}}
                """.formatted(opening, food, rail, road);
    }
}
