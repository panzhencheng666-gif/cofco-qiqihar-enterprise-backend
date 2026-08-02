package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class MarketDefinitionFailClosedIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;

    @ParameterizedTest
    @EnumSource(MetadataFault.class)
    void corruptedMetadataFailsDefinitionAndWriteClosedWithAStableTypedError(
            MetadataFault fault) throws Exception {
        inject(fault);

        mockMvc.perform(get("/api/v1/market-record-definitions")
                        .queryParam("productCode", "CORN")
                        .queryParam("objectTypeCode", "FEED_MILL"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DEFINITION_INVALID"));
        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "metadata-fault-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraft()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("MARKET_DEFINITION_INVALID"));
    }

    private void inject(MetadataFault fault) {
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
                ALTER TABLE platform.market_core_field_definition
                DROP CONSTRAINT market_core_field_definition_supported_metadata_check
                """).update();
        switch (fault) {
            case ILLEGAL_EXTENSION_CONTROL -> client.sql("""
                    UPDATE platform.market_core_field_definition
                    SET control_type = 'REGION_HIERARCHY'
                    WHERE code = 'MKT_SOURCE_NOTE'
                    """).update();
            case DUPLICATE_BINDING -> {
                client.sql("DROP INDEX platform.market_core_field_definition_typed_binding_unique")
                        .update();
                client.sql("""
                        DELETE FROM platform.market_core_field_applicability
                        WHERE field_code = 'MKT_SOURCE_NOTE'
                        """).update();
                client.sql("""
                        UPDATE platform.market_core_field_definition
                        SET domain_binding = 'REGION', control_type = 'REGION_HIERARCHY',
                            capability = 'GENERIC', required = true
                        WHERE code = 'MKT_SOURCE_NOTE'
                        """).update();
            }
            case BAD_CAPABILITY -> client.sql("""
                    UPDATE platform.market_core_field_definition
                    SET capability = 'PRICE_COMPONENT'
                    WHERE code = 'MKT_REGION'
                    """).update();
            case MISSING_EXTENSION_MAPPING -> client.sql("""
                    DELETE FROM platform.market_core_field_applicability
                    WHERE product_code = 'CORN' AND field_code = 'MKT_SOURCE_NOTE'
                    """).update();
            case EXTRA_EXTENSION_MAPPING -> {
                client.sql("""
                        ALTER TABLE platform.market_core_field_applicability
                        DROP CONSTRAINT market_core_field_applicabili_product_code_business_domain_fkey
                        """).update();
                client.sql("""
                        DELETE FROM platform.page_definition_field
                        WHERE product_code = 'CORN'
                          AND business_domain = 'MARKET'
                          AND page_kind = 'MONITORING'
                          AND field_code = 'MKT_SOURCE_NOTE'
                        """).update();
            }
        }
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

    enum MetadataFault {
        ILLEGAL_EXTENSION_CONTROL,
        DUPLICATE_BINDING,
        BAD_CAPABILITY,
        MISSING_EXTENSION_MAPPING,
        EXTRA_EXTENSION_MAPPING
    }
}
