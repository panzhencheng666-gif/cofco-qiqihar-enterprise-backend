package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class MarketRecordRestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void replaceTestProjectionFixtures() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM market.market_record_projection").update();
        jdbc.sql("""
                        INSERT INTO platform.page_filter_definition
                            (product_code, business_domain, page_kind, code, label,
                             control_type, placeholder, sort_order)
                        VALUES ('SOYBEAN', 'MARKET', 'QUALITY', 'subjectName', '记录名称',
                                'TEXT', '', 10)
                        ON CONFLICT (product_code, business_domain, page_kind, code)
                            DO NOTHING
                        """)
                .update();
        for (int index = 1; index <= 41; index++) {
            jdbc.sql("""
                            INSERT INTO market.market_record_projection
                                (record_id, product_code, business_domain, page_kind,
                                 observed_at, values)
                            VALUES (:recordId, 'SOYBEAN', 'MARKET', 'QUALITY',
                                    :observedAt, CAST(:values AS jsonb))
                            """)
                    .param("recordId", "record-" + index)
                    .param("observedAt", OffsetDateTime.parse("2026-08-02T00:00:00Z").plusMinutes(index))
                    .param("values", "{\"subjectName\":\"记录" + index
                            + "\",\"score\":" + index + ".5,\"note\":null}")
                    .update();
        }
    }

    @AfterEach
    void removeDeclaredFilterFixture() {
        JdbcClient.create(dataSource).sql("""
                        DELETE FROM platform.page_filter_definition
                        WHERE product_code = 'SOYBEAN'
                          AND business_domain = 'MARKET'
                          AND page_kind = 'QUALITY'
                          AND code IN ('subjectName', '.subjectName')
                        """)
                .update();
    }

    @Test
    void returnsCanonicalServerPagedProjectionForFirstSecondAndLastPages() throws Exception {
        assertPage(0, "记录1", 1.5, 20, 3);
        assertPage(1, "记录21", 21.5, 20, 3);
        assertPage(2, "记录41", 41.5, 1, 3);
    }

    @Test
    void rejectsAQueryWhosePageContextDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "CORN")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAGE_DEFINITION_NOT_FOUND"));
    }

    @Test
    void appliesDynamicValueFiltersInsideThePostgresProjection() throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .queryParam("filter.subjectName", "记录21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("record-21"));
    }

    @Test
    void rejectsRepeatedFilterValuesInsteadOfSelectingTheFirstOne() throws Exception {
        assertInvalidQuery(get("/api/v1/market-records")
                .queryParam("productCode", "SOYBEAN")
                .queryParam("pageKind", "QUALITY")
                .queryParam("pageNumber", "0")
                .queryParam("pageSize", "20")
                .queryParam("filter.subjectName", "", "记录21"));
    }

    @Test
    void rejectsRepeatedCoreParameters() throws Exception {
        assertInvalidQuery(get("/api/v1/market-records")
                .queryParam("productCode", "SOYBEAN")
                .queryParam("pageKind", "QUALITY")
                .queryParam("pageNumber", "0")
                .queryParam("pageSize", "20", "50"));
    }

    @Test
    void rejectsUnknownCoreParameterSpellings() throws Exception {
        assertInvalidQuery(get("/api/v1/market-records")
                .queryParam("productCode", "SOYBEAN")
                .queryParam("pageKind", "QUALITY")
                .queryParam("pageNumber", "0")
                .queryParam("pageSize", "20")
                .queryParam("pageNubmer", "2"));
    }

    @Test
    void rejectsAnEmptyFilterCode() throws Exception {
        assertInvalidQuery(get("/api/v1/market-records")
                .queryParam("productCode", "SOYBEAN")
                .queryParam("pageKind", "QUALITY")
                .queryParam("pageNumber", "0")
                .queryParam("pageSize", "20")
                .queryParam("filter.", "记录21"));
    }

    @Test
    void rejectsMalformedFilterParameterNames() throws Exception {
        JdbcClient.create(dataSource).sql("""
                        INSERT INTO platform.page_filter_definition
                            (product_code, business_domain, page_kind, code, label,
                             control_type, placeholder, sort_order)
                        VALUES ('SOYBEAN', 'MARKET', 'QUALITY', '.subjectName', '畸形测试字段',
                                'TEXT', '', 20)
                        """)
                .update();
        assertInvalidQuery(get("/api/v1/market-records")
                .queryParam("productCode", "SOYBEAN")
                .queryParam("pageKind", "QUALITY")
                .queryParam("pageNumber", "0")
                .queryParam("pageSize", "20")
                .queryParam("filter..subjectName", "记录21"));
    }

    @Test
    void rejectsBlankSingleFilterValuesInsteadOfExpandingTheResultSet() throws Exception {
        assertInvalidQuery(get("/api/v1/market-records")
                .queryParam("productCode", "SOYBEAN")
                .queryParam("pageKind", "QUALITY")
                .queryParam("pageNumber", "0")
                .queryParam("pageSize", "20")
                .queryParam("filter.subjectName", "   "));
    }

    @Test
    void rejectsPageSizesOutsideTheLoadedDefinition() throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD_QUERY"));
    }

    @Test
    void rejectsFilterCodesOutsideTheLoadedDefinition() throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", "0")
                        .queryParam("pageSize", "20")
                        .queryParam("filter.undeclared", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD_QUERY"));
    }

    private void assertPage(
            int pageNumber,
            String firstSubject,
            double firstScore,
            int itemCount,
            int totalPages) throws Exception {
        mockMvc.perform(get("/api/v1/market-records")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("pageKind", "QUALITY")
                        .queryParam("pageNumber", String.valueOf(pageNumber))
                        .queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNumber").value(pageNumber))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(41))
                .andExpect(jsonPath("$.data.totalPages").value(totalPages))
                .andExpect(jsonPath("$.data.items.length()").value(itemCount))
                .andExpect(jsonPath("$.data.items[0].values.subjectName").value(firstSubject))
                .andExpect(jsonPath("$.data.items[0].values.score").value(firstScore))
                .andExpect(jsonPath("$.data.items[0].values.note").value(nullValue()));
    }

    private void assertInvalidQuery(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD_QUERY"));
    }
}
