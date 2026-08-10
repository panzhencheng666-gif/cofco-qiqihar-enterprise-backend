package com.cofco.qiqihar.graintrade.market.interfaceadapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
@Transactional
@Rollback
class MarketObjectRestIntegrationTest {
    @Autowired MockMvc mockMvc;

    @Test
    void persistsOneMarketSubjectWithMultipleEffectiveRoles() throws Exception {
        String id = create("讷河阶段四米业");

        mockMvc.perform(get("/api/v1/market-objects").principal(() -> "market-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].objectId").value(id))
                .andExpect(jsonPath("$.data[0].objectName").value("讷河阶段四米业"))
                .andExpect(jsonPath("$.data[0].regionCode").value("230281"))
                .andExpect(jsonPath("$.data[0].regionName").value("讷河市"))
                .andExpect(jsonPath("$.data[0].productIds[0]").value("corn"))
                .andExpect(jsonPath("$.data[0].productIds[1]").value("paddy"))
                .andExpect(jsonPath("$.data[0].roles.length()").value(2))
                .andExpect(jsonPath("$.data[0].roles[?(@.roleId == 'rice-mill')]").exists())
                .andExpect(jsonPath("$.data[0].roles[?(@.roleId == 'trader')]").exists());
    }

    @Test
    void updatesTheSameDossierWithOptimisticLocking() throws Exception {
        String id = create("龙江县阶段四粮贸");

        mockMvc.perform(put("/api/v1/market-objects/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("龙江县阶段四粮贸（已核定）", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectId").value(id))
                .andExpect(jsonPath("$.data.objectName").value("龙江县阶段四粮贸（已核定）"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/v1/market-objects/{id}", id)
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("过期覆盖", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MARKET_OBJECT_VERSION_CONFLICT"));
    }

    @Test
    void rejectsAnUnauthenticatedWriteBeforeAcceptingItsBody() throws Exception {
        mockMvc.perform(post("/api/v1/market-objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedAuthenticatedRequestsAsClientErrors() throws Exception {
        mockMvc.perform(post("/api/v1/market-objects")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/market-objects")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("含空角色企业", null)
                                .replace("{\"roleId\":\"rice-mill\"", "null,{\"roleId\":\"rice-mill\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_OBJECT"));

        mockMvc.perform(post("/api/v1/market-objects")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("空产品列表企业", null)
                                .replace("\"productIds\":[\"corn\",\"paddy\"]", "\"productIds\":null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_OBJECT"));

        mockMvc.perform(put("/api/v1/market-objects/not-a-uuid")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("非法标识对象", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_OBJECT"));
    }

    @Test
    void doesNotMistakeANameForStablePartyIdentity() throws Exception {
        create("跨地区唯一粮食企业");

        mockMvc.perform(post("/api/v1/market-objects")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("跨地区唯一粮食企业", null)
                                .replace("\"regionCode\":\"230281\"", "\"regionCode\":\"230221\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regionCode").value("230221"));
    }

    @Test
    void rejectsACultivarThatDoesNotBelongToTheSelectedProduct() throws Exception {
        mockMvc.perform(post("/api/v1/market-objects")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("不匹配品种企业", null)
                                .replace("[\"corn\",\"paddy\"]", "[\"paddy\"]")
                                .replace("\"cultivarIds\":[]", "\"cultivarIds\":[\"heinong-84\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_OBJECT"));
    }

    private String create(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/market-objects")
                        .principal(() -> "market-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.objectTypeId").value("business-party"))
                .andExpect(jsonPath("$.data.objectTypeLabel").value("经营主体"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst("(?s).*?\\\"objectId\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private static String body(String name, Integer version) {
        String versionField = version == null ? "" : ",\"version\":" + version;
        return """
                {
                  "objectName":"%s",
                  "objectTypeId":"business-party",
                  "regionCode":"230281",
                  "productIds":["corn","paddy"],
                  "cultivarIds":[],
                  "sourceChannelId":"enterprise-report",
                  "responsiblePerson":"市场测试员",
                  "effectiveFrom":"2026-08-01",
                  "effectiveTo":null,
                  "validityStatus":"active",
                  "roles":[
                    {"roleId":"rice-mill","label":"米厂","effectiveFrom":"2026-08-01","effectiveTo":null,
                     "capabilityTemplateVersionId":"CAPABILITY-MARKET-rice-mill"},
                    {"roleId":"trader","label":"贸易商","effectiveFrom":"2026-08-01","effectiveTo":null,
                     "capabilityTemplateVersionId":"CAPABILITY-MARKET-trader"}
                  ]%s
                }
                """.formatted(name, versionField);
    }
}
