package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

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
                    .param("values", "{\"subjectName\":\"记录" + index + "\"}")
                    .update();
        }
    }

    @Test
    void returnsCanonicalServerPagedProjectionForFirstSecondAndLastPages() throws Exception {
        assertPage(0, "记录1", 20, 3);
        assertPage(1, "记录21", 20, 3);
        assertPage(2, "记录41", 1, 3);
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

    private void assertPage(int pageNumber, String firstSubject, int itemCount, int totalPages)
            throws Exception {
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
                .andExpect(jsonPath("$.data.items[0].values.subjectName").value(firstSubject));
    }
}
