package com.cofco.qiqihar.graintrade.logistics.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
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

    @BeforeEach
    void fixture() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE logistics.route_event,logistics.logistics_node RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM platform.logistics_core_field_applicability WHERE field_code='LOG_REFERENCE'").update();
        jdbc.sql("DELETE FROM platform.logistics_core_field_definition WHERE code='LOG_REFERENCE'").update();
        jdbc.sql("DELETE FROM platform.business_period WHERE code='LOG-2026-08'").update();
        jdbc.sql("""
                INSERT INTO platform.business_period(code,name,starts_on,ends_on,sort_order)
                VALUES('LOG-2026-08','2026年8月物流监测期','2026-08-01','2026-08-31',900)
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
    void databaseDefinitionControlsCodeKeyedFieldsNodeCodesExtensionsWorkflowAndCas() throws Exception {
        mvc.perform(get("/api/v1/logistics-record-definitions").queryParam("productCode", "CORN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_ORIGIN')].controlType").value("SELECT"))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_ORIGIN')].options[?(@.value == 'TEST_RAIL')]").exists())
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_STATUS')].readOnly").value(true))
                .andExpect(jsonPath("$.data.fields[?(@.code == 'LOG_REFERENCE')].label").value("运单编号"))
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
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
                .andExpect(jsonPath("$.data.values.LOG_ORIGIN").value("TEST_RAIL"))
                .andExpect(jsonPath("$.data.values.LOG_TRANSPORT_MODE").value("RAIL"))
                .andExpect(jsonPath("$.data.values.LOG_DIRECTION").value("INFLOW"))
                .andExpect(jsonPath("$.data.values.LOG_PERIOD").value("LOG-2026-08"))
                .andExpect(jsonPath("$.data.displayValues.LOG_ORIGIN").value("测试铁路站"))
                .andExpect(jsonPath("$.data.displayValues.LOG_TRANSPORT_MODE").value("铁路"))
                .andExpect(jsonPath("$.data.displayValues.LOG_DIRECTION").value("流入"))
                .andExpect(jsonPath("$.data.displayValues.LOG_PERIOD").value("2026年8月物流监测期"))
                .andExpect(jsonPath("$.data.displayValues.LOG_STATUS").value("已审核"))
                .andExpect(jsonPath("$.data.values.LOG_ROUTE_VOLUME").value("13.5000"))
                .andExpect(jsonPath("$.data.values.LOG_REFERENCE").value("WB-2026-001"))
                .andExpect(jsonPath("$.data.values.__originNodeId").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event
                WHERE aggregate_type = 'LOGISTICS_RECORD' AND aggregate_id = :id
                """).param("id", corn).query(Long.class).single()).isEqualTo(6L);

        mvc.perform(post("/api/v1/logistics-records").principal(() -> "logistics-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CORN", "RAIL", "TEST_RAIL", "TEST_ROAD", "12.500", true, null)
                                .replace("\"LOG_REFERENCE\":\"WB-2026-001\"",
                                        "\"UNKNOWN_FIELD\":\"not-authorized\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));
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
                        .principal(() -> "logistics-tester").contentType(MediaType.APPLICATION_JSON).content(body))
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
        String extensionValue = extension ? ",\"LOG_REFERENCE\":\"WB-2026-001\"" : "";
        String versionValue = version == null ? "" : ",\"version\":" + version;
        return """
                {"productCode":"%s","values":{"LOG_PERIOD":"LOG-2026-08","LOG_COLLECTION_DATE":"2026-08-01",
                 "LOG_ORIGIN":"%s","LOG_DESTINATION":"%s","LOG_TRANSPORT_MODE":"%s","LOG_DIRECTION":"INFLOW",
                 "LOG_ROUTE_VOLUME":"%s","LOG_FREIGHT_RATE":"80.25","LOG_TRANSIT_TIME":"2.50",
                 "LOG_SOURCE_ORGANIZATION":"测试来源单位","LOG_REPORTER":"测试填报人"%s}%s}
                """.formatted(product, origin, destination, mode, volume, extensionValue, versionValue);
    }
}
