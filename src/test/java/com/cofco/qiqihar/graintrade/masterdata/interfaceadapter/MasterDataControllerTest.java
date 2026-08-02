package com.cofco.qiqihar.graintrade.masterdata.interfaceadapter;

import com.cofco.qiqihar.graintrade.masterdata.application.MasterDataQuery;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.FieldDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MasterDataControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new MasterDataController(new ContractQuery()))
            .build();

    @Test
    void returnsRiceQualityFieldsInTheSuccessEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/page-definitions")
                        .queryParam("productCode", "RICE")
                        .queryParam("domain", "MARKET")
                        .queryParam("pageKind", "QUALITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value("RICE"))
                .andExpect(jsonPath("$.data.fields[*].name")
                        .value(contains("水分", "出米率", "出糙率", "杂质")));
    }

    @Test
    void returnsSoybeanQualityFieldsInTheSuccessEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/page-definitions")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("domain", "MARKET")
                        .queryParam("pageKind", "QUALITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields[*].name")
                        .value(contains("蛋白", "出油率", "不完善粒", "水分", "杂质")));
    }

    @Test
    void returnsOnlyObjectTypesApplicableToTheRequestedProduct() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/object-types")
                        .queryParam("productCode", "SOYBEAN")
                        .queryParam("domain", "MARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name")
                        .value(contains("贸易商", "深加工", "批发市场", "承储企业")));
    }

    @Test
    void returnsBusinessBatchesFilteredByPeriodInTheSuccessEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/master-data/business-batches")
                        .queryParam("businessPeriodCode", "PERIOD_2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name").value(contains("第一批", "第二批")))
                .andExpect(jsonPath("$.data[*].businessPeriodCode")
                        .value(contains("PERIOD_2026", "PERIOD_2026")));
    }

    private static final class ContractQuery implements MasterDataQuery {

        @Override
        public List<Region> regions() {
            return List.of();
        }

        @Override
        public List<Product> products() {
            return List.of();
        }

        @Override
        public List<Cultivar> cultivars(String productCode) {
            return List.of();
        }

        @Override
        public List<ObjectType> objectTypes(String productCode, String domain) {
            return List.of(
                    new ObjectType("TRADER", "贸易商", "MARKET"),
                    new ObjectType("DEEP_PROCESSOR", "深加工", "MARKET"),
                    new ObjectType("WHOLESALE_MARKET", "批发市场", "MARKET"),
                    new ObjectType("RESERVE_ENTERPRISE", "承储企业", "MARKET"));
        }

        @Override
        public List<BusinessPeriod> businessPeriods() {
            return List.of();
        }

        @Override
        public List<BusinessBatch> businessBatches(String businessPeriodCode) {
            return List.of(
                    new BusinessBatch("BATCH_1", "第一批", businessPeriodCode),
                    new BusinessBatch("BATCH_2", "第二批", businessPeriodCode));
        }

        @Override
        public PageDefinition pageDefinition(String productCode, String domain, String pageKind) {
            List<FieldDefinition> fields = "RICE".equals(productCode)
                    ? List.of(
                            field("MOISTURE", "水分", 10),
                            field("MILLING_YIELD", "出米率", 20),
                            field("BROWN_RICE_YIELD", "出糙率", 30),
                            field("IMPURITY", "杂质", 40))
                    : List.of(
                            field("PROTEIN", "蛋白", 10),
                            field("OIL_YIELD", "出油率", 20),
                            field("IMPERFECT_GRAIN", "不完善粒", 30),
                            field("MOISTURE", "水分", 40),
                            field("IMPURITY", "杂质", 50));
            return new PageDefinition(productCode, domain, pageKind, fields, null);
        }

        private FieldDefinition field(String code, String name, int sortOrder) {
            return new FieldDefinition(code, name, "DECIMAL", sortOrder);
        }
    }
}
