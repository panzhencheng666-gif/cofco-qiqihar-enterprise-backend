package com.cofco.qiqihar.graintrade.logistics.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
class LogisticsRestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    JdbcClient jdbc;

    @Test
    void onlyAnIndependentAuthorizedReviewerCanApproveOrReturnALogisticsRecord() throws Exception {
        String id=create("CORN","RAIL","TEST_RAIL","TEST_ROAD",true);
        transition(id,"submit",0,null)
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"));
        mvc.perform(post("/api/v1/logistics-records/{id}/approve",id)
                        .principal(() -> "logistics-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_APPROVAL_FORBIDDEN"));
        mvc.perform(post("/api/v1/logistics-records/{id}/return",id)
                        .principal(() -> "logistics-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"补充依据\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SELF_RETURN_FORBIDDEN"));
        transition(id,"approve",1,null).andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void voidsALogisticsDraftThroughHttpAndPersistsATerminalAuditedState() throws Exception {
        String id=create("CORN","RAIL","TEST_RAIL","TEST_ROAD",true);

        transition(id,"void",0,null)
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW"));

        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("SELECT status_code FROM logistics.route_event WHERE event_id::text=:id")
                        .param("id", id).query(String.class).single()).isEqualTo("VOIDED");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type='LOGISTICS_RECORD' AND aggregate_id=:id
                  AND action_code='LOGISTICS_RECORD_VOIDED'
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_event_outbox
                WHERE aggregate_type='LOGISTICS_RECORD' AND aggregate_id=:id
                  AND action_code='LOGISTICS_RECORD_VOIDED'
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        mvc.perform(post("/api/v1/logistics-records/{id}/submit",id)
                        .principal(() -> "logistics-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
    }

    @BeforeEach
    void fixture() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE logistics.route_event,logistics.logistics_node RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM platform.logistics_core_field_applicability WHERE field_code='LOG_REFERENCE'").update();
        jdbc.sql("DELETE FROM platform.logistics_core_field_definition WHERE code='LOG_REFERENCE'").update();
        jdbc.sql("DELETE FROM platform.business_period WHERE code='LOG-2026-08'").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order,marketing_year_code)
                VALUES('LOG-2026-08','2026年8月物流监测期','2026-08-01','2026-08-31',900,'2026/27')
                """).update();
        node(jdbc, "TEST_RAIL", "测试铁路站", "RAIL_NODE");
        node(jdbc, "TEST_ROAD", "测试公路节点", "ROAD_NODE");
        jdbc.sql("""
                INSERT INTO platform.logistics_core_field_definition(code,label,control_type,binding,required,sort_order)
                VALUES('LOG_REFERENCE','运单编号','TEXT','EXTENSION.LOG_REFERENCE',true,115)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.logistics_core_field_applicability(field_code,product_code,sort_order)
                VALUES('LOG_REFERENCE','CORN',115)
                """).update();
        jdbc.sql("""
                INSERT INTO platform.logistics_action_applicability(product_code,status_code,action_code)
                VALUES('CORN','PENDING_REVIEW','APPROVE') ON CONFLICT DO NOTHING
                """).update();
    }

    @AfterEach
    void clearAuditEvents() {
        jdbc.sql("TRUNCATE platform.business_audit_event").update();
    }

    @Test
    void rejectsPathologicalAndOverPrecisionDecimalsWithoutRouteWrites() throws Exception {
        for (String value : new String[] {"1E999999999", "100000000000000.0000", "1.00000"}) {
            mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", value, true, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
        }

        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("SELECT count(*) FROM logistics.route_event").query(Long.class).single()).isZero();
    }

    @Test
    void bindsLogisticsReporterToTheAuthenticatedEmployeeAndPreservesItOnRevision() throws Exception {
        String id = mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", "12.500", true, null)
                                .replace("客户端伪造账号", "客户端伪造姓名")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.values.LOG_REPORTER").value("物流测试员"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        mvc.perform(put("/api/v1/logistics-records/{id}", id).principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", "12.500", true, 0)
                                .replace("客户端伪造账号", "再次伪造姓名")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.values.LOG_REPORTER").value("物流测试员"));
    }

    @Test
    void exposesAndPersistsOnlyThePublicLogisticsSurveyContract() throws Exception {
        mvc.perform(get("/api/v1/logistics-record-definitions").queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'surveyYear')].label").value("数据年份"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'surveyMonth')].label").value("数据月份"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'fillingDate')].label").value("填报日期"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_FREIGHT_RATE')].label")
                        .value("物流运价（不含车板价）"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_BOARD_PRICE')].label").value("车板价"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_SURVEYOR_NAME')].label")
                        .value("调研人"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_SURVEYOR_PHONE')].label")
                        .value("调研人联系方式"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_SAMPLE_CONTACT')].label")
                        .value("物流样本点联系方式"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_REGION')].controlType").value("SELECT"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_REGION')].options[?(@.value == '230200')].label")
                        .value("齐齐哈尔市"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_PERIOD')]").doesNotExist())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_COLLECTION_DATE')]").doesNotExist())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_ORIGIN')]").doesNotExist())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_DESTINATION')]").doesNotExist())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_TRANSIT_TIME')]").doesNotExist());

        String id = mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(publicBody(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.values.surveyYear").value("2026"))
                .andExpect(jsonPath("$.data.values.surveyMonth").value("8"))
                .andExpect(jsonPath("$.data.values.LOG_REPORTER").value("物流测试员"))
                .andExpect(jsonPath("$.data.values.LOG_SURVEYOR_PHONE").value("13800000000"))
                .andExpect(jsonPath("$.data.values.LOG_SAMPLE_CONTACT").value("13900000000"))
                .andExpect(jsonPath("$.data.values.LOG_FREIGHT_RATE").value("80.2500"))
                .andExpect(jsonPath("$.data.values.LOG_BOARD_PRICE").value("2650.0000"))
                .andExpect(jsonPath("$.data.values.LOG_PERIOD").doesNotExist())
                .andExpect(jsonPath("$.data.values.LOG_ORIGIN").doesNotExist())
                .andExpect(jsonPath("$.data.values.LOG_TRANSIT_TIME").doesNotExist())
                .andExpect(jsonPath("$.data.values.__locationKey").doesNotExist())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT fact_code,value FROM logistics.route_fact
                WHERE event_id::text=:id AND fact_code IN ('FREIGHT_RATE','BOARD_PRICE')
                ORDER BY fact_code
                """).param("id", id).query((row, index) ->
                row.getString("fact_code") + "=" + row.getBigDecimal("value").toPlainString()).list())
                .containsExactly("BOARD_PRICE=2650.0000", "FREIGHT_RATE=80.2500");

        mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publicBody(null).replace("47.354300", "91.000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
    }

    @Test
    void publicDirectionAndRegionContinueToFeedTheLogisticsOverviewVolume() throws Exception {
        String id = mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(publicBody(null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        transition(id, "submit", 0, null);
        transition(id, "approve", 1, null);

        String outflow = mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publicBody(null).replace("INFLOW", "OUTFLOW")
                                .replace("12.5000", "7.0000")
                                .replace("80.2500", "70.0000")
                                .replace("2650.0000", "999999.0000")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        transition(outflow, "submit", 0, null);
        transition(outflow, "approve", 1, null);

        mvc.perform(get("/api/v1/overview/indicators").principal(() -> "logistics-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("periodCode", "LOG-2026-08").queryParam("marketingYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("12.5")))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].sourceCount").value(1))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("7")))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].sourceCount").value(1))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_AVERAGE_FREIGHT_RATE')].value")
                        .value(org.hamcrest.Matchers.hasItem("75.125")))
                .andExpect(jsonPath("$.data[?(@.code == 'LOGISTICS_AVERAGE_FREIGHT_RATE')].sourceCount")
                        .value(2));

        mvc.perform(get("/api/v1/overview/dashboard").principal(() -> "logistics-tester")
                        .queryParam("productCode", "CORN").queryParam("regionCode", "230200")
                        .queryParam("year", "2026").queryParam("periodCode", "LOG-2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'LOGISTICS_INFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("12.5")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'LOGISTICS_OUTFLOW_VOLUME')].value")
                        .value(org.hamcrest.Matchers.hasItem("7")))
                .andExpect(jsonPath("$.data.metrics[?(@.code == 'LOGISTICS_AVERAGE_FREIGHT_RATE')].value")
                        .value(org.hamcrest.Matchers.hasItem("75.125")));
    }

    @Test
    void publicDefinitionRejectsInjectedMetadataAndPreservesWorkflowAndCas() throws Exception {
        mvc.perform(get("/api/v1/logistics-record-definitions").queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'surveyYear')].label").value("数据年份"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_STATUS')].readOnly").value(true))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_REFERENCE')]").doesNotExist())
                .andExpect(jsonPath("$.data.actions[?(@.code == 'APPROVE')]").exists());
        mvc.perform(post("/api/v1/logistics-records/not-disclosed/submit")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized());

        String corn = create("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", true);
        create("SOYBEAN", "ROAD", "TEST_ROAD", "TEST_RAIL", false);
        create("RICE", "RAIL", "TEST_RAIL", "TEST_ROAD", false);
        mvc.perform(get("/api/v1/logistics-records").queryParam("productCode", "SOYBEAN")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20")
                        .queryParam("filter.transportModeCode", "ROAD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].productCode").value("SOYBEAN"));

        transition(corn, "submit", 0, null).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        transition(corn, "return", 1, "补充运单编号").andExpect(jsonPath("$.data.status").value("RETURNED"));
        mvc.perform(put("/api/v1/logistics-records/{id}", corn).principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", "13.500", true, 2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RETURNED"))
                .andExpect(jsonPath("$.data.returnReason").value("补充运单编号"))
                .andExpect(jsonPath("$.data.version").value(3));
        transition(corn, "submit", 3, null);
        jdbc.sql("""
                DELETE FROM platform.logistics_action_applicability
                WHERE product_code='CORN' AND status_code='PENDING_REVIEW' AND action_code='APPROVE'
                """).update();
        mvc.perform(post("/api/v1/logistics-records/{id}/approve", corn)
                        .principal(() -> "logistics-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
        mvc.perform(get("/api/v1/logistics-records/{id}", corn))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.allowedActions[?(@ == 'APPROVE')]").doesNotExist());
        jdbc.sql("""
                INSERT INTO platform.logistics_action_applicability(product_code,status_code,action_code)
                VALUES('CORN','PENDING_REVIEW','APPROVE')
                """).update();
        transition(corn, "approve", 4, null).andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(put("/api/v1/logistics-records/{id}", corn).principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", "99", true, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOGISTICS_RECORD_VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/logistics-records/{id}", corn))
                .andExpect(jsonPath("$.data.values.LOG_REGION").value("230200"))
                .andExpect(jsonPath("$.data.values.LOG_TRANSPORT_MODE").value("RAIL"))
                .andExpect(jsonPath("$.data.values.LOG_DIRECTION").value("INFLOW"))
                .andExpect(jsonPath("$.data.values.surveyYear").value("2026"))
                .andExpect(jsonPath("$.data.values.surveyMonth").value("8"))
                .andExpect(jsonPath("$.data.displayValues.LOG_REGION").value("齐齐哈尔市"))
                .andExpect(jsonPath("$.data.displayValues.LOG_TRANSPORT_MODE").value("铁路"))
                .andExpect(jsonPath("$.data.displayValues.LOG_DIRECTION").value("流入"))
                .andExpect(jsonPath("$.data.displayValues.LOG_STATUS").value("已审核"))
                .andExpect(jsonPath("$.data.values.LOG_ROUTE_VOLUME").value("13.5000"))
                .andExpect(jsonPath("$.data.values.LOG_BOARD_PRICE").value("2650.0000"))
                .andExpect(jsonPath("$.data.values.LOG_REFERENCE").doesNotExist())
                .andExpect(jsonPath("$.data.values.__originNodeId").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'LOGISTICS_RECORD' AND aggregate_id = :id
                """).param("id", corn).query(Long.class).single()).isEqualTo(6L);

        mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", "12.500", true, null)
                                .replace("\"LOG_BOARD_PRICE\":\"2650.0000\"",
                                        "\"UNKNOWN_FIELD\":\"not-authorized\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
    }

    @Test
    void filtersByExplicitSurveyPeriodRealFillingTimeAndStatus() throws Exception {
        String id = create("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", true);
        jdbc.sql("""
                UPDATE logistics.route_event
                SET created_at=TIMESTAMPTZ '2026-08-05 09:00:00+08',
                    reported_at=TIMESTAMPTZ '2030-01-01 09:00:00+08'
                WHERE event_id::text=:id
                """).param("id", id).update();

        mvc.perform(get("/api/v1/logistics-records")
                        .queryParam("productCode", "CORN").queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20").queryParam("filter.surveyYear", "2026")
                        .queryParam("filter.surveyMonth", "8")
                        .queryParam("filter.regionCode", "230200")
                        .queryParam("filter.fillingDateFrom", "2026-08-05")
                        .queryParam("filter.fillingDateTo", "2026-08-05")
                        .queryParam("filter.status", "DRAFT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].values.surveyYear").value("2026"))
                .andExpect(jsonPath("$.data.items[0].values.surveyMonth").value("8"))
                .andExpect(jsonPath("$.data.items[0].values.fillingDate").value("2026-08-05"))
                .andExpect(jsonPath("$.data.items[0].values.LOG_SURVEY_PERIOD_PRECISION").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].values.LOG_FILLING_TIME_BASIS").doesNotExist());

        mvc.perform(get("/api/v1/logistics-records")
                        .queryParam("productCode", "CORN").queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20").queryParam("filter.periodCode", "LOG-2026-08"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/logistics-records")
                        .queryParam("productCode", "CORN").queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20").queryParam("filter.nodeTypeCode", "RAIL_NODE"))
                .andExpect(status().isBadRequest());

        jdbc.sql("""
                UPDATE logistics.route_event SET survey_month=NULL,survey_period_precision='YEAR'
                WHERE event_id::text=:id
                """).param("id", id).update();
        mvc.perform(get("/api/v1/logistics-records")
                        .queryParam("productCode", "CORN").queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20").queryParam("filter.surveyYear", "2026")
                        .queryParam("filter.surveyMonth", "8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/logistics-records")
                        .queryParam("productCode", "CORN").queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20").queryParam("filter.surveyYear", "2026"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));

        transition(id, "submit", 0, null);
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT submitted_at IS NOT NULL FROM logistics.route_event WHERE event_id::text=:id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        jdbc.sql("""
                UPDATE logistics.route_event SET submitted_at=TIMESTAMPTZ '2026-08-06 10:30:00+08'
                WHERE event_id::text=:id
                """).param("id", id).update();
        mvc.perform(get("/api/v1/logistics-records")
                        .queryParam("productCode", "CORN").queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20").queryParam("filter.surveyYear", "2026")
                        .queryParam("filter.fillingDateFrom", "2026-08-06")
                        .queryParam("filter.fillingDateTo", "2026-08-06")
                        .queryParam("filter.status", "PENDING_REVIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].values.fillingDate").value("2026-08-06"))
                .andExpect(jsonPath("$.data.items[0].values.LOG_FILLING_TIME_BASIS").doesNotExist());
    }

    private String create(String product, String mode, String origin, String destination, boolean extension)
            throws Exception {
        return mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(product, mode, origin, destination, "12.500", extension, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.values.LOG_TRANSPORT_MODE").value(mode))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            String id, String action, long version, String reason) throws Exception {
        String body = reason == null ? "{\"version\":" + version + "}"
                : "{\"version\":" + version + ",\"reason\":\"" + reason + "\"}";
        return mvc.perform(post("/api/v1/logistics-records/{id}/" + action, id)
                        .principal(() -> Set.of("approve", "return").contains(action)
                                ? "production-tester" : "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static void node(JdbcClient jdbc, String code, String name, String type) {
        jdbc.sql("""
                INSERT INTO logistics.logistics_node(node_code,node_name,node_type_code,region_code)
                VALUES(:code,:name,:type,'230200')
                """).param("code", code).param("name", name).param("type", type).update();
    }

    private static String body(String product, String mode, String origin, String destination,
            String volume, boolean extension, Integer version) {
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"%s","values":{"surveyYear":"2026","surveyMonth":"8",
                 "LOG_SAMPLE_NAME":"齐齐哈尔物流样本点","LOG_REGION":"230200",
                 "LOG_TRANSPORT_MODE":"%s","LOG_DIRECTION":"INFLOW","LOG_ROUTE_VOLUME":"%s",
                 "LOG_FREIGHT_RATE":"80.2500","LOG_BOARD_PRICE":"2650.0000",
                 "LOG_REPORTER":"客户端伪造账号","LOG_SURVEYOR_NAME":"王雷",
                 "LOG_SURVEYOR_PHONE":"13800000000",
                 "LOG_SAMPLE_CONTACT":"13900000000","LOG_SAMPLE_LATITUDE":"47.354300",
                 "LOG_SAMPLE_LONGITUDE":"123.918200"}%s}
                """.formatted(product, mode, volume, versionValue);
    }

    private static String publicBody(Integer version) {
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"CORN","values":{"surveyYear":"2026","surveyMonth":"8",
                 "LOG_SAMPLE_NAME":"齐齐哈尔物流样本点","LOG_REGION":"230200",
                 "LOG_TRANSPORT_MODE":"RAIL","LOG_DIRECTION":"INFLOW",
                 "LOG_ROUTE_VOLUME":"12.5000","LOG_FREIGHT_RATE":"80.2500",
                 "LOG_BOARD_PRICE":"2650.0000","LOG_REPORTER":"客户端伪造账号",
                 "LOG_SURVEYOR_NAME":"王雷","LOG_SURVEYOR_PHONE":"13800000000",
                 "LOG_SAMPLE_CONTACT":"13900000000",
                 "LOG_SAMPLE_LATITUDE":"47.354300","LOG_SAMPLE_LONGITUDE":"123.918200"}%s}
                """.formatted(versionValue);
    }
}
