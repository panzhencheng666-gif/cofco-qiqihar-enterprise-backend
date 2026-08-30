package com.cofco.qiqihar.graintrade.designsample.metadata.interfaceadapter;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class DesignSampleMetadataRestIntegrationTest {
    private static final String ENDPOINT = "/api/v1/design-sample-field-definitions";
    private static final List<Context> LEGAL_CONTEXTS = List.of(
            context("PRODUCTION", "CORN", "FARMER"),
            context("PRODUCTION", "SOYBEAN", "FARMER"),
            context("PRODUCTION", "RICE", "FARMER"),
            context("PRODUCTION", "CORN", "VILLAGE_COMMITTEE"),
            context("PRODUCTION", "SOYBEAN", "VILLAGE_COMMITTEE"),
            context("PRODUCTION", "RICE", "VILLAGE_COMMITTEE"),
            context("PRODUCTION", "CORN", "AGRICULTURAL_TECH_STATION"),
            context("PRODUCTION", "SOYBEAN", "AGRICULTURAL_TECH_STATION"),
            context("PRODUCTION", "RICE", "AGRICULTURAL_TECH_STATION"),
            context("MARKET", "CORN", "TRADER"),
            context("MARKET", "SOYBEAN", "TRADER"),
            context("MARKET", "RICE", "TRADER"),
            context("MARKET", "CORN", "DEEP_PROCESSOR"),
            context("MARKET", "SOYBEAN", "DEEP_PROCESSOR"),
            context("MARKET", "RICE", "DEEP_PROCESSOR"),
            context("MARKET", "CORN", "WHOLESALE_MARKET"),
            context("MARKET", "SOYBEAN", "WHOLESALE_MARKET"),
            context("MARKET", "RICE", "WHOLESALE_MARKET"),
            context("MARKET", "CORN", "RESERVE_ENTERPRISE"),
            context("MARKET", "SOYBEAN", "RESERVE_ENTERPRISE"),
            context("MARKET", "RICE", "RESERVE_ENTERPRISE"),
            context("MARKET", "RICE", "RICE_MILL"),
            context("MARKET", "CORN", "BREEDING_FACTORY"),
            context("MARKET", "CORN", "FEED_MILL"),
            context("MARKET", "CORN", "AGRICULTURAL_INPUT_STORE"),
            context("MARKET", "SOYBEAN", "AGRICULTURAL_INPUT_STORE"),
            context("MARKET", "RICE", "AGRICULTURAL_INPUT_STORE"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;

    @Test
    void exposesTwoDomainsThreeProductsElevenObjectsAndAllTwentySevenStableContexts()
            throws Exception {
        for (Context context : LEGAL_CONTEXTS) {
            definition(context)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contractVersion").value("design-sample-fields-v1"))
                    .andExpect(jsonPath("$.contractDigest")
                            .value(matchesPattern("sha256:[a-f0-9]{64}")))
                    .andExpect(jsonPath("$.context.domainCode").value(context.domainCode()))
                    .andExpect(jsonPath("$.context.productCode").value(context.productCode()))
                    .andExpect(jsonPath("$.context.objectTypeCode")
                            .value(context.objectTypeCode()));
        }

        definition(LEGAL_CONTEXTS.getFirst())
                .andExpect(jsonPath("$.domains", hasSize(2)))
                .andExpect(jsonPath("$.products", hasSize(3)))
                .andExpect(jsonPath("$.objectTypes", hasSize(11)))
                .andExpect(jsonPath("$.supportedContexts", hasSize(27)));
    }

    @Test
    void fixesObjectSpecificFieldsTraderPricesAndAgriculturalInputStoreIsolation()
            throws Exception {
        for (Context context : LEGAL_CONTEXTS.stream()
                .filter(candidate -> candidate.objectTypeCode().equals("TRADER"))
                .toList()) {
            definition(context)
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(hasItem("MKT_PURCHASE_BASE_PRICE")))
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(hasItem("MKT_SALE_BASE_PRICE")));
        }

        for (Context context : LEGAL_CONTEXTS.stream()
                .filter(candidate -> candidate.domainCode().equals("MARKET"))
                .filter(candidate -> !candidate.objectTypeCode().equals("TRADER"))
                .filter(candidate -> !candidate.objectTypeCode().equals("AGRICULTURAL_INPUT_STORE"))
                .toList()) {
            definition(context)
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(hasItem("MKT_PURCHASE_BASE_PRICE")))
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(not(hasItem("MKT_SALE_BASE_PRICE"))));
        }

        for (String productCode : List.of("CORN", "SOYBEAN", "RICE")) {
            definition(context("MARKET", productCode, "AGRICULTURAL_INPUT_STORE"))
                    .andExpect(jsonPath("$.observationFields[*].code").value(containsInAnyOrder(
                            "OBSERVED_ON",
                            "AGRI_INPUT_SEED_SALES_VOLUME",
                            "AGRI_INPUT_SEED_RETAIL_PRICE",
                            "AGRI_INPUT_SUPPLY_STATUS",
                            "AGRI_INPUT_PLANTING_INTENTION_TREND")))
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(not(hasItem("MKT_PURCHASE_BASE_PRICE"))))
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(not(hasItem("MKT_SALE_BASE_PRICE"))))
                    .andExpect(jsonPath("$.observationFields[*].code")
                            .value(not(hasItem("MOISTURE"))));
        }

        definition(context("PRODUCTION", "CORN", "VILLAGE_COMMITTEE"))
                .andExpect(jsonPath("$.observationFields[*].code")
                        .value(not(hasItem("PROD_OPENING_INVENTORY"))))
                .andExpect(jsonPath("$.observationFields[*].code")
                        .value(not(hasItem("MOISTURE"))));
    }

    @Test
    void returnsContractErrorsForInvalidContextsAndInapplicableFieldsEvenWhenNull()
            throws Exception {
        definition(context("MARKET", "SOYBEAN", "FEED_MILL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DESIGN_SAMPLE_CONTEXT"));

        String digest = digest(context("MARKET", "CORN", "WHOLESALE_MARKET"));
        mockMvc.perform(post(ENDPOINT + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(
                                context("MARKET", "CORN", "WHOLESALE_MARKET"),
                                digest,
                                "{\"MKT_SALE_BASE_PRICE\":null}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_NOT_APPLICABLE"));
    }

    @Test
    void distinguishesUnknownFromRealZeroWithoutManufacturingNotApplicableValues()
            throws Exception {
        Context context = context("MARKET", "CORN", "TRADER");
        String digest = digest(context);

        mockMvc.perform(post(ENDPOINT + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(
                                context,
                                digest,
                                requiredValues("\"MKT_PURCHASE_BASE_PRICE\":0,\"MOISTURE\":null"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueStates.MKT_PURCHASE_BASE_PRICE").value("KNOWN"))
                .andExpect(jsonPath("$.valueStates.MOISTURE").value("UNKNOWN"))
                .andExpect(jsonPath("$.valueStates.MKT_SALE_BASE_PRICE").doesNotExist());
    }

    @Test
    void rejectsMissingRequiredEditableFieldsAndDecimalIntegerOverflow() throws Exception {
        Context context = context("MARKET", "CORN", "TRADER");
        String digest = digest(context);

        mockMvc.perform(post(ENDPOINT + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(
                                context,
                                digest,
                                "{\"OBSERVED_ON\":\"2026-08-30\",\"MKT_PURCHASE_BASE_PRICE\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUIRED_FIELD_MISSING"));

        mockMvc.perform(post(ENDPOINT + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(
                                context,
                                digest,
                                requiredValues("\"MKT_PURCHASE_BASE_PRICE\":1e18"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_VALUE_INVALID"));
    }

    @Test
    void supportsDigestBoundConditionalCaching()
            throws Exception {
        Context context = context("PRODUCTION", "RICE", "FARMER");
        MvcResult first = definition(context)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=300")))
                .andExpect(header().string(HttpHeaders.ETAG,
                        matchesPattern("\"sha256:[a-f0-9]{64}:PRODUCTION:RICE:FARMER\"")))
                .andReturn();

        Context otherContext = context("MARKET", "CORN", "TRADER");
        mockMvc.perform(get(ENDPOINT)
                        .queryParam("domainCode", otherContext.domainCode())
                        .queryParam("productCode", otherContext.productCode())
                        .queryParam("objectTypeCode", otherContext.objectTypeCode())
                        .header(HttpHeaders.IF_NONE_MATCH,
                                first.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG,
                        not(first.getResponse().getHeader(HttpHeaders.ETAG))));

        mockMvc.perform(get(ENDPOINT)
                        .queryParam("domainCode", context.domainCode())
                        .queryParam("productCode", context.productCode())
                        .queryParam("objectTypeCode", context.objectTypeCode())
                        .header(HttpHeaders.IF_NONE_MATCH, first.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, first.getResponse().getHeader(HttpHeaders.ETAG)));
    }

    private org.springframework.test.web.servlet.ResultActions definition(Context context)
            throws Exception {
        return mockMvc.perform(get(ENDPOINT)
                .queryParam("domainCode", context.domainCode())
                .queryParam("productCode", context.productCode())
                .queryParam("objectTypeCode", context.objectTypeCode()));
    }

    private String digest(Context context) throws Exception {
        MvcResult result = definition(context).andExpect(status().isOk()).andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        return response.get("contractDigest").asText();
    }

    private String validationRequest(Context context, String digest, String values) {
        return """
                {"contractVersion":"design-sample-fields-v1","contractDigest":"%s",
                 "context":{"domainCode":"%s","productCode":"%s","objectTypeCode":"%s"},
                 "values":%s}
                """.formatted(
                digest,
                context.domainCode(),
                context.productCode(),
                context.objectTypeCode(),
                values);
    }

    private String requiredValues(String additionalValues) {
        return """
                {"DSP_NAME":"验收点","DSP_REGION_CODE":"230200",
                 "DSP_LONGITUDE":123.95,"DSP_LATITUDE":47.35,
                 "OBSERVED_ON":"2026-08-30",%s}
                """.formatted(additionalValues);
    }

    private static Context context(String domainCode, String productCode, String objectTypeCode) {
        return new Context(domainCode, productCode, objectTypeCode);
    }

    private record Context(String domainCode, String productCode, String objectTypeCode) {}
}
