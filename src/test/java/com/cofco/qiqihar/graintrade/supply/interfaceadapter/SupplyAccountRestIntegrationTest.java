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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                TRUNCATE supply.source_adoption_set,supply.source_release,supply.manual_input_decision,supply.approved_adjustment,
                  supply.adoption_decision,supply.calculation_run,production.production_record,
                  market.market_record,logistics.route_event,logistics.logistics_node,
                  platform.business_audit_event RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("DELETE FROM supply.formula_version WHERE version_no>1").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES('2026-Q3','2026年第三季度',DATE '2026-07-01',DATE '2026-09-30',202603)
                ON CONFLICT(code) DO NOTHING
                """).update();
    }

    @AfterEach
    void cleanAfterEach() {
        clean();
    }

    @Test
    void controlledSourcesDriveAllProductsAndTrialVersionsNeverAdvanceDecisions() throws Exception {
        mvc.perform(post("/api/v1/supply-accounts/runs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/supply-input-sets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        for (String product : List.of("CORN", "SOYBEAN", "RICE")) {
            if (product.equals("CORN")) controlledCornSources();
            else manualSources(product);
            String inputSet = inputSet(product, 0, Map.of());
            mvc.perform(get("/api/v1/supply-input-workspaces")
                            .queryParam("productCode", product)
                            .queryParam("regionCode", "230200")
                            .queryParam("marketingYear", "2026/27"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.inputSetVersion").value(1))
                    .andExpect(jsonPath("$.data.latestInputSetId").value(inputSet))
                    .andExpect(jsonPath("$.data.roles.length()").value(14))
                    .andExpect(jsonPath("$.data.roles[?(@.code == 'LOCAL_PRODUCTION')].manualAllowed")
                            .value(true))
                    .andExpect(jsonPath("$.data.roles[?(@.code == 'LOCAL_PRODUCTION')].releases.length()")
                            .value(1));
            mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                            .contentType(MediaType.APPLICATION_JSON).content(runBody(product, inputSet, "1.000", 0)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value(product))
                    .andExpect(jsonPath("$.data.resultState").value("FORMAL"))
                    .andExpect(jsonPath("$.data.resultVersion").value(1))
                    .andExpect(jsonPath("$.data.decisionVersion").value(0))
                    .andExpect(jsonPath("$.data.balanced").value(true))
                    .andExpect(jsonPath("$.data.publishable").value(true))
                    .andExpect(jsonPath("$.data.inventoryReconciliationDifference").value("-0.250"))
                    .andExpect(jsonPath("$.data.inputSetId").value(inputSet))
                    .andExpect(jsonPath("$.data.legacyReadOnly").value(false))
                    .andExpect(jsonPath("$.data.adjustmentProposal").doesNotExist())
                    .andExpect(jsonPath("$.data.adjustmentAudit.reason").value("库存覆盖差异调整建议"))
                    .andExpect(jsonPath("$.data.adjustmentAudit.actor").value("supply-reviewer"))
                    .andExpect(jsonPath("$.data.formula.differenceExpression")
                            .value("SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY"))
                    .andExpect(jsonPath("$.data.sources.length()").value(14));
        }

        manual("CORN", "SURVEYED_ENDING_INVENTORY", "100.000", 0).andExpect(status().isOk());
        String trialInputSet = inputSet("CORN", 1, Map.of());
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", trialInputSet, "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("TRIAL"))
                .andExpect(jsonPath("$.data.resultVersion").value(2))
                .andExpect(jsonPath("$.data.decisionVersion").value(0))
                .andExpect(jsonPath("$.data.publishable").value(false))
                .andExpect(jsonPath("$.data.balanceReason").value("OUTSIDE_BALANCE_TOLERANCE"))
                .andExpect(jsonPath("$.data.adjustmentAudit").doesNotExist())
                .andExpect(jsonPath("$.data.adjustmentProposal.value").value("1.000"))
                .andExpect(jsonPath("$.data.adjustmentProposal.reason").value("库存覆盖差异调整建议"))
                .andExpect(jsonPath("$.data.adjustmentProposal.requestedBy").value("supply-reviewer"));

        manual("CORN", "SURVEYED_ENDING_INVENTORY", "7.750", 1).andExpect(status().isOk());
        String repairedInputSet = inputSet("CORN", 2, Map.of());
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", repairedInputSet, "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("FORMAL"))
                .andExpect(jsonPath("$.data.resultVersion").value(3))
                .andExpect(jsonPath("$.data.decisionVersion").value(1))
                .andExpect(jsonPath("$.data.adjustmentProposal").doesNotExist())
                .andExpect(jsonPath("$.data.adjustmentAudit.reason").value("库存覆盖差异调整建议"));
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", repairedInputSet, "1.000", 99)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUPPLY_DECISION_VERSION_CONFLICT"));
        assertThat(jdbc.sql("SELECT count(*) FROM supply.calculation_run WHERE product_code='CORN'")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT action_code FROM platform.business_audit_event ORDER BY occurred_at, event_id")
                .query(String.class).list()).contains("SUPPLY_SOURCE_RELEASED", "SUPPLY_MANUAL_INPUT_APPROVED",
                        "SUPPLY_INPUT_SET_CREATED", "SUPPLY_ACCOUNT_CALCULATED", "SUPPLY_ACCOUNT_PUBLISHED");
    }

    @Test
    void rejectsUnapprovedOrInexactProvenanceAndHistoryUsesImmutableSnapshots() throws Exception {
        String priceRecord = marketRecord("CORN", "APPROVED", "3.000");
        release("MARKET", priceRecord, 0, "CORN", "OPENING_INVENTORY",
                "MKT_ACTUAL_TRADE_PRICE", "PASSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_SOURCE_MAPPING"));

        manual("RICE", "OPENING_INVENTORY", "3.000", 0).andExpect(status().isOk());
        manual("RICE", "OPENING_INVENTORY", "4.000", 0).andExpect(status().isOk());
        List<String> openingReleases = jdbc.sql("""
                SELECT release.source_release_id::text FROM supply.source_release release
                JOIN supply.source_release_binding binding ON binding.source_release_id=release.source_release_id
                WHERE release.product_code='RICE' AND binding.role_code='OPENING_INVENTORY'
                ORDER BY release.source_version
                """).query(String.class).list();
        mvc.perform(post("/api/v1/supply-input-sets").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"productCode":"RICE","regionCode":"230200","marketingYear":"2026/27",
                                 "reason":"明确采用本期核定来源","expectedVersion":0,"items":[
                                   {"roleCode":"OPENING_INVENTORY","sourceReleaseId":"%s"},
                                   {"roleCode":"LOCAL_PRODUCTION","sourceReleaseId":"%s"}]}
                                """.formatted(openingReleases.getFirst(), openingReleases.getFirst())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_INPUT_SET"));
        mvc.perform(post("/api/v1/supply-input-sets").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"productCode":"RICE","regionCode":"230200","marketingYear":"2026/27",
                                 "reason":"明确采用本期核定来源","expectedVersion":0,"items":[
                                   {"roleCode":"OPENING_INVENTORY","sourceReleaseId":"%s"},
                                   {"roleCode":"OPENING_INVENTORY","sourceReleaseId":"%s"}]}
                                """.formatted(openingReleases.getFirst(), openingReleases.getLast())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_INPUT_SET"));

        String draft = productionRecord("CORN", "DRAFT", "3.000");
        release("PRODUCTION", draft, 0, "CORN", "LOCAL_PRODUCTION", "PROD_ESTIMATED_OUTPUT", "PASSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_SOURCE_PROVENANCE"));
        String approved = productionRecord("CORN", "APPROVED", "3.000");
        release("PRODUCTION", approved, 9, "CORN", "LOCAL_PRODUCTION", "PROD_ESTIMATED_OUTPUT", "PASSED")
                .andExpect(status().isBadRequest());
        release("PRODUCTION", approved, 0, "CORN", "LOCAL_PRODUCTION", "PROD_YIELD_PER_MU", "PASSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_SOURCE_MAPPING"));
        String realVolume = logisticsRecord("CORN", "APPROVED", "12500.000", "吨", "INFLOW");
        release("LOGISTICS", realVolume, 0, "CORN", "EXTERNAL_INFLOW", "ROUTE_VOLUME", "PASSED")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value").value("1.2500"))
                .andExpect(jsonPath("$.data.unitCode").value("万吨"));
        String wrongUnit = logisticsRecord("CORN", "APPROVED", "3.000", "元/吨", "INFLOW");
        release("LOGISTICS", wrongUnit, 0, "CORN", "EXTERNAL_INFLOW", "ROUTE_VOLUME", "PASSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_SOURCE_MAPPING"));

        controlledCornSources();
        String inputSet = inputSet("CORN", 0, Map.of());
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", inputSet, "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("FORMAL"))
                .andExpect(jsonPath("$.data.formula.version").value(1));
        long v1 = jdbc.sql("SELECT formula_version_id FROM supply.formula_version WHERE code='GRAIN_BALANCE' AND version_no=1")
                .query(Long.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE supply.formula_term SET coefficient=9
                WHERE formula_version_id=:id AND result_role='TOTAL_SUPPLY' AND operand_role='LOCAL_PRODUCTION'
                """).param("id", v1).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO supply.formula_term(formula_version_id,result_role,operand_role,coefficient,term_order)
                VALUES(:id,'TOTAL_SUPPLY','LEGACY_TAMPER_TERM',1,99)
                """).param("id", v1).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO supply.formula_result_role(formula_version_id,result_role,label,required,sort_order)
                VALUES(:id,'LEGACY_TAMPER_RESULT','篡改结果',false,99)
                """).param("id", v1).update()).hasMessageContaining("immutable");
        installFormulaV2();
        mvc.perform(post("/api/v1/supply-accounts/runs").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody("CORN", inputSet, "1.000", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultState").value("TRIAL"))
                .andExpect(jsonPath("$.data.formula.version").value(2))
                .andExpect(jsonPath("$.data.formula.tolerance").value("0.501"))
                .andExpect(jsonPath("$.data.totalSupply").value("18.000"))
                .andExpect(jsonPath("$.data.decisionVersion").value(0));
        String productionId = jdbc.sql("SELECT source_record_id FROM supply.source_release WHERE source_domain='PRODUCTION'")
                .query(String.class).list().getLast();
        jdbc.sql("UPDATE production.production_record SET cultivated_area_mu=999 WHERE record_id=:id")
                .param("id", productionId).update();
        mvc.perform(get("/api/v1/supply-accounts").queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200").queryParam("marketingYear", "2026/27")
                        .queryParam("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].formula.version").value(1))
                .andExpect(jsonPath("$.data[0].sources[?(@.roleCode == 'LOCAL_PRODUCTION')].sourceValue")
                        .value("3.0000"));

        String releaseId = jdbc.sql("SELECT source_release_id::text FROM supply.source_release LIMIT 1")
                .query(String.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE supply.source_release SET quality_state='WARNING' WHERE source_release_id::text=:id")
                .param("id", releaseId).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM supply.source_release_binding WHERE source_release_id::text=:id")
                .param("id", releaseId).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE supply.source_adoption_set SET reason='篡改' WHERE input_set_id::text=:id")
                .param("id", inputSet).update()).hasMessageContaining("immutable");
        long mappingId = jdbc.sql("""
                SELECT mapping_id FROM supply.role_source_applicability
                WHERE product_code='CORN' AND role_code='LOCAL_PRODUCTION' AND source_domain='PRODUCTION'
                ORDER BY mapping_version DESC LIMIT 1
                """).query(Long.class).single();
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE supply.role_source_applicability SET conversion_factor=2
                WHERE mapping_id=:id
                """).param("id", mappingId).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM supply.role_source_applicability WHERE mapping_id=:id")
                .param("id", mappingId).update()).hasMessageContaining("immutable");
    }

    private void controlledCornSources() throws Exception {
        String production = productionRecord("CORN", "APPROVED", "3.000");
        release("PRODUCTION", production, 0, "CORN", "LOCAL_PRODUCTION", "PROD_ESTIMATED_OUTPUT", "PASSED")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value").value("3.0000"));
        String inflowLogistics = logisticsRecord("CORN", "APPROVED", "30000.000", "吨", "INFLOW");
        String outflowLogistics = logisticsRecord("CORN", "APPROVED", "1.000", "万吨", "OUTFLOW");
        for (String role : ROLES) {
            if (role.equals("LOCAL_PRODUCTION")) continue;
            if (role.equals("EXTERNAL_INFLOW") || role.equals("EXTERNAL_OUTFLOW")) {
                release("LOGISTICS", role.equals("EXTERNAL_INFLOW") ? inflowLogistics : outflowLogistics,
                        0, "CORN", role, "ROUTE_VOLUME", "PASSED")
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
                         "roleCode":"%s","value":"%s","reason":"人工核定依据完整","expectedVersion":%d}
                        """.formatted(product, role, value, expected)));
    }

    private org.springframework.test.web.servlet.ResultActions release(String domain, String record, long version,
            String product, String role, String field, String quality) throws Exception {
        return mvc.perform(post("/api/v1/supply-sources/releases").principal(() -> "supply-reviewer")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceDomain":"%s","sourceRecordId":"%s","sourceVersion":%d,"productCode":"%s",
                         "regionCode":"230200","marketingYear":"2026/27","roleCode":"%s",
                         "sourceFieldCode":"%s","qualityState":"%s"}
                        """.formatted(domain, record, version, product, role, field, quality)));
    }

    private void installFormulaV2() {
        jdbc.sql("""
                INSERT INTO supply.formula_version(code,version_no,name,precision_value,scale_value,tolerance,
                  difference_code,difference_label,difference_expression,active,rounding_mode)
                VALUES('GRAIN_BALANCE',2,'粮食供需平衡公式V2',18,3,0.5005,
                  'INVENTORY_RECONCILIATION_DIFFERENCE','库存核对差额',
                  'SURVEYED_ENDING_INVENTORY - ADOPTED_ENDING_INVENTORY',true,'HALF_UP')
                """).update();
        long v1 = jdbc.sql("SELECT formula_version_id FROM supply.formula_version WHERE code='GRAIN_BALANCE' AND version_no=1")
                .query(Long.class).single();
        long v2 = jdbc.sql("SELECT formula_version_id FROM supply.formula_version WHERE code='GRAIN_BALANCE' AND version_no=2")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO supply.formula_result_role(formula_version_id,result_role,label,required,sort_order)
                SELECT :v2,result_role,label,required,sort_order FROM supply.formula_result_role
                WHERE formula_version_id=:v1
                """).param("v2", v2).param("v1", v1).update();
        jdbc.sql("""
                INSERT INTO supply.formula_term(formula_version_id,result_role,operand_role,coefficient,term_order)
                SELECT :v2,result_role,operand_role,
                  CASE WHEN result_role='TOTAL_SUPPLY' AND operand_role='LOCAL_PRODUCTION' THEN 2 ELSE coefficient END,
                  term_order FROM supply.formula_term WHERE formula_version_id=:v1
                """).param("v2", v2).param("v1", v1).update();
    }

    private String productionRecord(String product, String status, String output) {
        String id = UUID.randomUUID().toString();
        String object = objectType(product, "PRODUCTION");
        jdbc.sql("""
                INSERT INTO production.production_record(record_id,product_code,object_type_code,region_code,
                  survey_date,reported_at,cultivated_area_mu,yield_per_mu_kg,status_code,last_modified_by)
                VALUES(:id,:product,:object,'230200',current_date,now(),10000000,:output,:status,'tester')
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

    private String logisticsRecord(String product, String status, String value, String unit, String direction) {
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
                  o.region_code,o.node_id,o.node_code,d.region_code,d.node_id,d.node_code,'RAIL',:direction,'测试单位','tester',:status,'tester','tester',now(),now()
                FROM logistics.logistics_node o,logistics.logistics_node d WHERE o.node_code='N-A' AND d.node_code='N-B'
                """).param("id", id).param("product", product).param("direction", direction)
                .param("status", status).update();
        jdbc.sql("INSERT INTO logistics.route_fact(event_id,fact_code,value,unit_code) VALUES(CAST(:id AS uuid),'ROUTE_VOLUME',:value,:unit)")
                .param("id", id).param("value", new BigDecimal(value)).param("unit", unit).update();
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

    private String inputSet(String product, long expectedVersion, Map<String, String> overrides) throws Exception {
        Map<String, String> selections = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (binding.role_code) binding.role_code,release.source_release_id::text
                FROM supply.source_release release JOIN supply.source_release_binding binding
                  ON binding.source_release_id=release.source_release_id
                WHERE release.product_code=:product AND release.region_code='230200'
                  AND release.marketing_year='2026/27' AND release.approval_state='APPROVED'
                ORDER BY binding.role_code,release.source_version DESC,release.approved_at DESC
                """).param("product", product).query((row, index) -> Map.entry(
                        row.getString("role_code"), row.getString("source_release_id")))
                .list().forEach(entry -> selections.put(entry.getKey(), entry.getValue()));
        selections.putAll(overrides);
        StringBuilder items = new StringBuilder();
        for (String role : ROLES) {
            if (!items.isEmpty()) items.append(',');
            items.append("{\"roleCode\":\"").append(role).append("\",\"sourceReleaseId\":\"")
                    .append(selections.get(role)).append("\"}");
        }
        mvc.perform(post("/api/v1/supply-input-sets").principal(() -> "supply-reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"productCode":"%s","regionCode":"230200","marketingYear":"2026/27",
                                 "reason":"明确采用本期核定来源","expectedVersion":%d,"items":[%s]}
                                """.formatted(product, expectedVersion, items)))
                .andExpect(status().isOk());
        return jdbc.sql("""
                SELECT input_set_id::text FROM supply.source_adoption_set
                WHERE product_code=:product AND region_code='230200' AND marketing_year='2026/27'
                ORDER BY version_no DESC LIMIT 1
                """).param("product", product).query(String.class).single();
    }

    private static String runBody(String product, String inputSetId, String adjustment, long expected) {
        return """
                {"productCode":"%s","regionCode":"230200","marketingYear":"2026/27","inputSetId":"%s",
                 "adjustmentProposalValue":"%s","adjustmentProposalReason":"库存覆盖差异调整建议",
                 "expectedDecisionVersion":%d,"publish":true}
                """.formatted(product, inputSetId, adjustment, expected);
    }
}
