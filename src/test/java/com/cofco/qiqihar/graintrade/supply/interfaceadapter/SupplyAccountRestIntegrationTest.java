package com.cofco.qiqihar.graintrade.supply.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class SupplyAccountRestIntegrationTest {
    private static final List<String> ROLES = List.of(
            "OPENING_INVENTORY", "LOCAL_PRODUCTION", "EXTERNAL_INFLOW", "IMPORTS", "OTHER_SUPPLY",
            "FOOD_USE", "FEED_USE", "SEED_USE", "PROCESSING_USE", "LOSS", "EXTERNAL_OUTFLOW",
            "EXPORTS", "OTHER_USE", "SURVEYED_ENDING_INVENTORY");
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE supply.source_release,supply.manual_input_decision,supply.approved_adjustment,
                  supply.adoption_decision,supply.calculation_run,production.production_record,
                  market.market_record,logistics.route_event,logistics.logistics_node RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603)
                ON CONFLICT(code) DO NOTHING
                """).update();
    }

    @Test
    void controlledSourcesDriveAllProductsAndTrialVersionsNeverAdvanceDecisions() throws Exception {
        mvc.perform(post("/api/v1/supply-accounts/runs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized());
        for (String product : List.of("CORN", "SOYBEAN", "RICE")) {
            if (product.equals("CORN")) controlledCornSources();
            else manualSources(product);
            mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                            .contentType(MediaType.APPLICATION_JSON).content(runBody(product, "1.000", 0)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value(product))
                    .andExpect(jsonPath("$.data.resultState").value("FORMAL"))
                    .andExpect(jsonPath("$.data.resultVersion").value(1))
                    .andExpect(jsonPath("$.data.decisionVersion").value(0))
                    .andExpect(jsonPath("$.data.balanced").value(true))
                    .andExpect(jsonPath("$.data.publishable").value(true))
                    .andExpect(jsonPath("$.data.inventoryReconciliationDifference").value("-0.250"))
                    .andExpect(jsonPath("$.data.adjustmentAudit.reason").value("库存覆盖差异经复核"))
                    .andExpect(jsonPath("$.data.adjustmentAudit.actor").value("supply-reviewer"))
                    .andExpect(jsonPath("$.data.formula.differenceExpression")
                            .value("SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY"))
                    .andExpect(jsonPath("$.data.sources.length()").value(14));
        }

        String blockingRecord = marketRecord("CORN", "APPROVED", "3.000");
        release("MARKET", blockingRecord, 0, "CORN", "OPENING_INVENTORY",
                "MKT_ACTUAL_TRADE_PRICE", "BLOCKING").andExpect(status().isOk());
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("TRIAL"))
                .andExpect(jsonPath("$.data.resultVersion").value(2))
                .andExpect(jsonPath("$.data.decisionVersion").value(0))
                .andExpect(jsonPath("$.data.publishable").value(false))
                .andExpect(jsonPath("$.data.balanceReason").value("QUALITY_BLOCKING_SOURCE"));

        manual("CORN", "OPENING_INVENTORY", "3.000", 0).andExpect(status().isOk());
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("FORMAL"))
                .andExpect(jsonPath("$.data.resultVersion").value(3))
                .andExpect(jsonPath("$.data.decisionVersion").value(1));

        manual("CORN", "SURVEYED_ENDING_INVENTORY", "100.000", 0).andExpect(status().isOk());
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", "1.000", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("TRIAL"))
                .andExpect(jsonPath("$.data.resultVersion").value(4))
                .andExpect(jsonPath("$.data.decisionVersion").value(1))
                .andExpect(jsonPath("$.data.balanced").value(false))
                .andExpect(jsonPath("$.data.publishable").value(false))
                .andExpect(jsonPath("$.data.balanceReason").value("OUTSIDE_BALANCE_TOLERANCE"));
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", "1.000", 99)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUPPLY_DECISION_VERSION_CONFLICT"));
        assertThat(jdbc.sql("SELECT count(*) FROM supply.calculation_run WHERE product_code='CORN'")
                .query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void rejectsUnapprovedOrInexactProvenanceAndHistoryUsesImmutableSnapshots() throws Exception {
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", "0.000", 0)
                                .replace("2026/27", "2027/28")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("TRIAL"))
                .andExpect(jsonPath("$.data.validationCodes[0]").value("MISSING_REQUIRED_SOURCE"));

        String draft = marketRecord("CORN", "DRAFT", "3.000");
        release("MARKET", draft, 0, "CORN", "OPENING_INVENTORY", "MKT_ACTUAL_TRADE_PRICE", "PASSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_SOURCE_PROVENANCE"));
        jdbc.sql("UPDATE market.market_record SET status_code='RETURNED',return_reason='补充依据' WHERE record_id=:id")
                .param("id", draft).update();
        release("MARKET", draft, 0, "CORN", "OPENING_INVENTORY", "MKT_ACTUAL_TRADE_PRICE", "PASSED")
                .andExpect(status().isBadRequest());

        String approved = marketRecord("CORN", "APPROVED", "3.000");
        release("MARKET", approved, 9, "CORN", "OPENING_INVENTORY", "MKT_ACTUAL_TRADE_PRICE", "PASSED")
                .andExpect(status().isBadRequest());
        release("MARKET", approved, 0, "CORN", "OPENING_INVENTORY", "NO_SUCH_FIELD", "PASSED")
                .andExpect(status().isBadRequest());

        controlledCornSources();
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("FORMAL"));
        String productionId = jdbc.sql("SELECT source_record_id FROM supply.source_release WHERE source_domain='PRODUCTION'")
                .query(String.class).single();
        jdbc.sql("UPDATE production.production_record SET cultivated_area_mu=999 WHERE record_id=:id")
                .param("id", productionId).update();
        mvc.perform(get("/api/v1/supply-accounts").queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200").queryParam("marketingYear", "2026/27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sources[?(@.roleCode == 'LOCAL_PRODUCTION')].sourceValue")
                        .value("3.0000"));

        String releaseId = jdbc.sql("SELECT source_release_id::text FROM supply.source_release LIMIT 1")
                .query(String.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE supply.source_release SET quality_state='WARNING' WHERE source_release_id::text=:id")
                .param("id", releaseId).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM supply.source_release_binding WHERE source_release_id::text=:id")
                .param("id", releaseId).update()).hasMessageContaining("immutable");
    }

    private void controlledCornSources() throws Exception {
        String production = productionRecord("CORN", "APPROVED", "3.000");
        release("PRODUCTION", production, 0, "CORN", "LOCAL_PRODUCTION", "PROD_ESTIMATED_OUTPUT", "PASSED")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value").value("3.0000"));
        String market = marketRecord("CORN", "APPROVED", "3.000");
        String inflowLogistics = logisticsRecord("CORN", "APPROVED", "3.000");
        String outflowLogistics = logisticsRecord("CORN", "APPROVED", "1.000");
        for (String role : ROLES) {
            if (role.equals("LOCAL_PRODUCTION")) continue;
            if (role.equals("EXTERNAL_INFLOW") || role.equals("EXTERNAL_OUTFLOW")) {
                release("LOGISTICS", role.equals("EXTERNAL_INFLOW") ? inflowLogistics : outflowLogistics,
                        0, "CORN", role, "ROUTE_VOLUME", "PASSED")
                        .andExpect(status().isOk());
            } else if (role.equals("OPENING_INVENTORY")) {
                release("MARKET", market, 0, "CORN", role, "MKT_ACTUAL_TRADE_PRICE", "PASSED")
                        .andExpect(status().isOk());
            } else {
                manual("CORN", role, value(role), 0).andExpect(status().isOk());
            }
        }
    }

    private void manualSources(String product) throws Exception {
        for (String role : ROLES) manual(product, role, value(role), 0).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions manual(
            String product, String role, String value, long expected) throws Exception {
        return mvc.perform(post("/api/v1/supply-inputs/manual-decisions").principal(() -> "supply-reviewer")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"productCode":"%s","regionCode":"230200","marketingYear":"2026/27",
                         "roleCode":"%s","value":"%s","unitCode":"万吨","reason":"人工核定依据完整","expectedVersion":%d}
                        """.formatted(product, role, value, expected)));
    }

    private org.springframework.test.web.servlet.ResultActions release(String domain, String record, long version,
            String product, String role, String field, String quality) throws Exception {
        return mvc.perform(post("/api/v1/supply-sources/releases").principal(() -> "supply-reviewer")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceDomain":"%s","sourceRecordId":"%s","sourceVersion":%d,"productCode":"%s",
                         "regionCode":"230200","marketingYear":"2026/27","roleCode":"%s",
                         "sourceFieldCode":"%s","unitCode":"万吨","qualityState":"%s"}
                        """.formatted(domain, record, version, product, role, field, quality)));
    }

    private String productionRecord(String product, String status, String output) {
        String id = UUID.randomUUID().toString();
        String object = objectType(product, "PRODUCTION");
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,:product,:object,'230200',current_date,now(),1,:output,:status,'tester')
                """).param("id", id).param("product", product).param("object", object)
                .param("output", new BigDecimal(output)).param("status", status).update();
        return id;
    }

    private String marketRecord(String product, String status, String price) {
        String id = UUID.randomUUID().toString();
        String object = objectType(product, "MARKET");
        jdbc.sql("""
                INSERT INTO market.market_record(record_id,product_code,object_type_code,region_code,trade_date,
                  reported_at,purchase_base_price,trade_direction,status_code,last_modified_by)
                VALUES(:id,:product,:object,'230200',current_date,now(),:price,'PURCHASE',:status,'tester')
                """).param("id", id).param("product", product).param("object", object)
                .param("price", new BigDecimal(price)).param("status", status).update();
        return id;
    }

    private String logisticsRecord(String product, String status, String value) {
        String type = objectType(product, "LOGISTICS");
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES('N-A','A',:type,'230200'),('N-B','B',:type,'230202') ON CONFLICT(node_code) DO NOTHING
                """)
                .param("type", type).update();
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO logistics.route_event(event_id,product_code,monitoring_period_code,collection_date,reported_at,
                  origin_region_code,origin_node_id,origin_node_code,destination_region_code,destination_node_id,destination_node_code,transport_mode_code,
                  direction_code,source_organization,reporter,status_code,created_by,last_modified_by,created_at,updated_at)
                SELECT CAST(:id AS uuid),:product,(SELECT code FROM platform.business_period LIMIT 1),current_date,now(),
                  o.region_code,o.node_id,o.node_code,d.region_code,d.node_id,d.node_code,'RAIL','INFLOW','测试单位','tester',:status,'tester','tester',now(),now()
                FROM logistics.logistics_node o,logistics.logistics_node d WHERE o.node_code='N-A' AND d.node_code='N-B'
                """).param("id", id).param("product", product).param("status", status).update();
        jdbc.sql("INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code) VALUES(CAST(:id AS uuid),'ROUTE_VOLUME',:value,'万吨')")
                .param("id", id).param("value", new BigDecimal(value)).update();
        return id;
    }

    private String objectType(String product, String domain) {
        return jdbc.sql("""
                SELECT object.code FROM platform.object_type object JOIN platform.product_object_type link
                  ON link.object_type_code=object.code WHERE link.product_code=:product AND object.business_domain=:domain
                ORDER BY object.sort_order LIMIT 1
                """).param("product", product).param("domain", domain).query(String.class).single();
    }

    private static String value(String role) {
        if (role.equals("SURVEYED_ENDING_INVENTORY")) return "7.750";
        return switch (role) {
            case "OPENING_INVENTORY", "LOCAL_PRODUCTION", "EXTERNAL_INFLOW", "IMPORTS", "OTHER_SUPPLY" -> "3.000";
            default -> "1.000";
        };
    }

    private static String runBody(String product, String adjustment, long expected) {
        return """
                {"productCode":"%s","regionCode":"230200","marketingYear":"2026/27","approvedAdjustment":"%s",
                 "adoptionReason":"采用本期核定来源","adjustmentReason":"库存覆盖差异经复核","expectedDecisionVersion":%d,"publish":true}
                """.formatted(product, adjustment, expected);
    }
}
