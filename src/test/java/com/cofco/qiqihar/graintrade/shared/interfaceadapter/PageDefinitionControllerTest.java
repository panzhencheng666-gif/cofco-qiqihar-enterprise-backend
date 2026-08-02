package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageDefinition;
import com.cofco.qiqihar.graintrade.shared.domain.BusinessPageKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PageDefinitionControllerTest {

    @Test
    void exposesTheCompleteDefinitionAtTheCanonicalRoute() throws Exception {
        PageDefinitionQuery query = key -> fixture(key);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PageDefinitionController(query)).build();

        mockMvc.perform(get("/api/v1/page-definitions/MARKET/COLLECTION")
                        .queryParam("productCode", "SOYBEAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("MARKET"))
                .andExpect(jsonPath("$.data.pageKind").value("COLLECTION"))
                .andExpect(jsonPath("$.data.productCode").value("SOYBEAN"))
                .andExpect(jsonPath("$.data.breadcrumbs[*].label")
                        .value(contains("市场监测", "大豆市场采集")))
                .andExpect(jsonPath("$.data.filters[0].options[*].label")
                        .value(contains("待审核", "已核定")))
                .andExpect(jsonPath("$.data.defaultContext.collectionDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.columnGroups[0].fields[*].label")
                        .value(contains("蛋白", "出油率")))
                .andExpect(jsonPath("$.data.actions[0].label").value("查看"))
                .andExpect(jsonPath("$.data.pagination.defaultPageSize").value(20))
                .andExpect(jsonPath("$.data.pagination.pageSizeOptions", contains(20, 50)));
    }

    @Test
    void exposesAProductIndependentDefinitionWithoutAProductQueryParameter() throws Exception {
        PageDefinitionQuery query = key -> fixture(key);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PageDefinitionController(query)).build();

        mockMvc.perform(get("/api/v1/page-definitions/WORKFLOW/WORK_ITEMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("WORKFLOW"))
                .andExpect(jsonPath("$.data.pageKind").value("WORK_ITEMS"))
                .andExpect(jsonPath("$.data.productCode").doesNotExist());
    }

    private BusinessPageDefinition fixture(BusinessPageKey key) {
        return new BusinessPageDefinition(
                key,
                "大豆市场采集表",
                List.of(
                        new BusinessPageDefinition.Breadcrumb("market", "市场监测"),
                        new BusinessPageDefinition.Breadcrumb("collection", "大豆市场采集")),
                List.of(new BusinessPageDefinition.Filter(
                        "status",
                        "填报状态",
                        BusinessPageDefinition.FilterControl.SELECT,
                        "全部状态",
                        List.of(
                                new BusinessPageDefinition.Option("PENDING", "待审核"),
                                new BusinessPageDefinition.Option("APPROVED", "已核定")))),
                Map.of("collectionDate", "2026-08-01"),
                List.of(new BusinessPageDefinition.ColumnGroup(
                        "quality",
                        "质量",
                        List.of(
                                new BusinessPageDefinition.Field("protein", "蛋白", "DECIMAL", "%", null),
                                new BusinessPageDefinition.Field("oilYield", "出油率", "DECIMAL", "%", null)))),
                List.of(new BusinessPageDefinition.Action("view", "查看", BusinessPageDefinition.ActionScope.ROW)),
                new BusinessPageDefinition.Pagination(20, List.of(20, 50)));
    }
}
