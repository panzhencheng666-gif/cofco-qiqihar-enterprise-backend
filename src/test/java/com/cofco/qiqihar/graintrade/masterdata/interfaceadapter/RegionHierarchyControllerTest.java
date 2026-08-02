package com.cofco.qiqihar.graintrade.masterdata.interfaceadapter;

import com.cofco.qiqihar.graintrade.masterdata.application.MasterDataQuery;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegionHierarchyControllerTest {

    @Test
    void requestsOnlyOneParentLevelAtATime() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RegionHierarchyController(new RegionQuery()))
                .build();

        mockMvc.perform(get("/api/v1/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].label").value(contains("齐齐哈尔市")));
        mockMvc.perform(get("/api/v1/regions").queryParam("parentCode", "230200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].label").value(contains("龙沙区", "建华区")));
    }

    private static final class RegionQuery implements MasterDataQuery {
        @Override
        public List<Region> regions() {
            return List.of();
        }

        @Override
        public List<Region> regionChildren(String parentCode) {
            return parentCode == null
                    ? List.of(new Region("230200", "齐齐哈尔市", null, "PREFECTURE"))
                    : List.of(
                            new Region("230202", "龙沙区", parentCode, "COUNTY"),
                            new Region("230203", "建华区", parentCode, "COUNTY"));
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
            return List.of();
        }

        @Override
        public List<BusinessPeriod> businessPeriods() {
            return List.of();
        }

        @Override
        public List<BusinessBatch> businessBatches(String businessPeriodCode) {
            return List.of();
        }

        @Override
        public PageDefinition pageDefinition(String productCode, String domain, String pageKind) {
            throw new UnsupportedOperationException();
        }
    }
}
