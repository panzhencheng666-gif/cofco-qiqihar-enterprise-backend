package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Transactional
@Rollback
class MarketDataFailClosedIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;

    @Test
    void typedAndExtensionCodeCollisionFailsListAndDetailClosedWithTheSameTypedError()
            throws Exception {
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "data-fault-test")
                        .contentType(MediaType.APPLICATION_JSON).content(validDraft()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
                ALTER TABLE market.market_record_core_value
                DROP CONSTRAINT market_record_core_value_applicability_fk
                """).update();
        client.sql("""
                ALTER TABLE market.market_record_core_value
                DROP CONSTRAINT market_record_core_value_extension_binding_check
                """).update();
        client.sql("""
                INSERT INTO market.market_record_core_value(
                    record_id, product_code, field_code, domain_binding, value)
                VALUES (:id, 'CORN', 'MKT_REGION', 'REGION', '伪造地区')
                """).param("id", id).update();

        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DATA_INTEGRITY"));
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DATA_INTEGRITY"));
    }

    @Test
    void historicallyInapplicableFactFailsListAndDetailClosedWithTheSameTypedError()
            throws Exception {
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "data-fault-test")
                        .contentType(MediaType.APPLICATION_JSON).content(validDraft()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
                INSERT INTO market.market_record_fact(
                    record_id, fact_code, value, product_code, object_type_code)
                VALUES (:id, 'TEST_WEIGHT', 720, 'CORN', 'FEED_MILL')
                """).param("id", id).update();
        client.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        client.sql("""
                ALTER TABLE market.market_record_fact
                DROP CONSTRAINT market_record_fact_header_context_fk
                """).update();
        client.sql("""
                UPDATE market.market_record
                SET product_code = 'SOYBEAN', object_type_code = 'DEEP_PROCESSOR'
                WHERE record_id = :id
                """).param("id", id).update();

        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "SOYBEAN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DATA_INTEGRITY"));
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DATA_INTEGRITY"));
    }

    @Test
    void nonCollidingUnmountedExtensionFailsListAndDetailClosedWithTheSameTypedError()
            throws Exception {
        String id = mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "data-fault-test")
                        .contentType(MediaType.APPLICATION_JSON).content(validDraft()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+).*", "$1");
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
                INSERT INTO platform.market_core_field_definition(
                    code, label, control_type, sort_order, description,
                    domain_binding, capability, required)
                VALUES ('MKT_DIRTY_UNMOUNTED', '未挂载脏扩展', 'TEXT', 9401, NULL,
                        'EXTENSION', 'GENERIC', false)
                """).update();
        client.sql("""
                ALTER TABLE market.market_record_core_value
                DROP CONSTRAINT market_record_core_value_applicability_fk
                """).update();
        client.sql("""
                INSERT INTO market.market_record_core_value(
                    record_id, product_code, field_code, domain_binding, value)
                VALUES (:id, 'CORN', 'MKT_DIRTY_UNMOUNTED', 'EXTENSION', '脏值')
                """).param("id", id).update();

        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN").queryParam("pageKind", "MONITORING")
                        .queryParam("pageNumber", "0").queryParam("pageSize", "20"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DATA_INTEGRITY"));
        mockMvc.perform(get("/api/v1/market-records/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DATA_INTEGRITY"));
    }

    private String validDraft() {
        return """
                {"productCode":"CORN","coreValues":{
                 "MKT_OBJECT_TYPE":"FEED_MILL","MKT_REGION":"230200",
                 "MKT_TRADE_DATE":"2026-08-01","MKT_TRADE_DIRECTION":"PURCHASE",
                 "MKT_PURCHASE_BASE_PRICE":"2300","MKT_SALE_BASE_PRICE":null,
                 "MKT_CARRIAGE_BOARD_AMOUNT":"36","MKT_PACKAGING_AMOUNT":"12",
                 "MKT_FREIGHT_AMOUNT":"72","MKT_PACKAGING_FORM":"BULK"},
                 "facts":{"PURCHASE_VOLUME":"12","MOISTURE":"14.6"}}
                """;
    }
}
