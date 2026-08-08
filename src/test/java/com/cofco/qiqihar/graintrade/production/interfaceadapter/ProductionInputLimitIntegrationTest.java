package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.logistics.application.LogisticsService;
import com.cofco.qiqihar.graintrade.logistics.interfaceadapter.LogisticsController;
import com.cofco.qiqihar.graintrade.market.application.MarketMonitoringService;
import com.cofco.qiqihar.graintrade.market.interfaceadapter.MarketMonitoringCommandController;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.supply.application.SupplyAccountService;
import com.cofco.qiqihar.graintrade.supply.interfaceadapter.SupplyAccountController;
import com.cofco.qiqihar.graintrade.testsupport.TestSecurityConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = {
        ProductionRecordController.class,
        MarketMonitoringCommandController.class,
        LogisticsController.class,
        SupplyAccountController.class,
        ProductionInputLimitIntegrationTest.JsonProbeController.class
})
@Import({TestSecurityConfiguration.class, ProductionInputLimitIntegrationTest.JsonProbeController.class})
@ContextConfiguration(classes = GrainTradeApplication.class)
@ActiveProfiles("test")
class ProductionInputLimitIntegrationTest {
    private static final int JSON_BODY_LIMIT = 1024 * 1024;
    private static final ClientRequestException SERVICE_REACHED =
            new ClientRequestException("SERVICE_REACHED", "Request reached persistence service");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ProductionRecordService productionService;
    @MockitoBean MarketMonitoringService marketService;
    @MockitoBean LogisticsService logisticsService;
    @MockitoBean SupplyAccountService supplyService;
    @MockitoBean AccessControl accessControl;
    @MockitoBean com.cofco.qiqihar.graintrade.logistics.application.CurrentActor logisticsActor;
    @MockitoBean com.cofco.qiqihar.graintrade.market.application.CurrentActor marketActor;
    @MockitoBean com.cofco.qiqihar.graintrade.supply.application.CurrentActor supplyActor;

    @BeforeEach
    void authenticatedBusinessActors() {
        org.mockito.Mockito.when(logisticsActor.currentActor()).thenReturn(Optional.of(
                new com.cofco.qiqihar.graintrade.logistics.application.AuthenticatedActor("input-limit-tester")));
        org.mockito.Mockito.when(marketActor.currentActor()).thenReturn(Optional.of(
                new com.cofco.qiqihar.graintrade.market.application.AuthenticatedActor("input-limit-tester")));
        org.mockito.Mockito.when(supplyActor.currentActor()).thenReturn(Optional.of(
                new com.cofco.qiqihar.graintrade.supply.application.AuthenticatedActor("input-limit-tester")));
    }

    @Test
    void productionRejectsExponentBombBeforePersistence() throws Exception {
        doThrow(SERVICE_REACHED).when(productionService).create(any());

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBody("1E999999999", Map.of(), Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));

        verify(productionService, never()).create(any());
    }

    @Test
    void productionRejectsMoreThan256AggregateMapEntriesBeforePersistence() throws Exception {
        doThrow(SERVICE_REACHED).when(productionService).create(any());
        Map<String, String> quality = entries("QUALITY_", 200, "1.0000");
        Map<String, String> costs = entries("COST_", 57, "1.0000");

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBody("1.0000", quality, costs)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));

        verify(productionService, never()).create(any());
    }

    @Test
    void productionCountsBusinessTextInUnicodeCodePoints() throws Exception {
        doThrow(SERVICE_REACHED).when(productionService).create(any());
        String fiveHundredCodePoints = "\uD83C\uDF3E".repeat(500);
        String fiveHundredOneCodePoints = fiveHundredCodePoints + "\uD83C\uDF3E";

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBodyWithProductCode(fiveHundredCodePoints)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SERVICE_REACHED"));

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBodyWithProductCode(fiveHundredOneCodePoints)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));
    }

    @Test
    void exactDecimalAndCollectionBoundariesPassWhileExtraPrecisionIsRejected() throws Exception {
        doThrow(SERVICE_REACHED).when(productionService).create(any());
        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBody("99999999999999.9999", entries("QUALITY_", 256, "1"), Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SERVICE_REACHED"));

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBody("100000000000000.0000", Map.of(), Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));

        mockMvc.perform(post("/api/v1/production-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productionBody("1.00000", Map.of(), Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));
    }

    @Test
    void marketRejectsExponentAndAggregateMapOverflowBeforePersistence() throws Exception {
        doThrow(SERVICE_REACHED).when(marketService).create(any());
        Map<String, String> core = entries("CORE_", 128, "text");
        Map<String, String> facts = entries("FACT_", 129, "1.0000");
        facts.put("EXPONENT", "1E999999999");
        Map<String, Object> body = Map.of("productCode", "CORN", "coreValues", core, "facts", facts);

        mockMvc.perform(post("/api/v1/market-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_MARKET_RECORD"));

        verify(marketService, never()).create(any());
    }

    @Test
    void logisticsRejectsOversizedMapAndTextBeforePersistence() throws Exception {
        doThrow(SERVICE_REACHED).when(logisticsService).create(any());
        Map<String, String> values = entries("LOG_", 257, "value");
        values.put("LOG_0", "\uD83C\uDF3E".repeat(501));

        mockMvc.perform(post("/api/v1/logistics-records")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("productCode", "CORN", "values", values))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_LOGISTICS_RECORD"));

        verify(logisticsService, never()).create(any());
    }

    @Test
    void supplyRejectsExponentAndOversizedItemListBeforePersistence() throws Exception {
        doThrow(SERVICE_REACHED).when(supplyService).approveManual(any());
        mockMvc.perform(post("/api/v1/supply-inputs/manual-decisions")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productCode":"CORN","regionCode":"230200","marketingYear":"2026/27",
                                 "roleCode":"BEGINNING_INVENTORY","value":"1E999999999","reason":"test",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_ACCOUNT_REQUEST"));
        verify(supplyService, never()).approveManual(any());

        doThrow(SERVICE_REACHED).when(supplyService).createInputSet(any());
        var items = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> Map.of("roleCode", "ROLE_" + index, "sourceReleaseId", "release-" + index))
                .toList();
        mockMvc.perform(post("/api/v1/supply-input-sets")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "productCode", "CORN", "regionCode", "230200", "marketingYear", "2026/27",
                                "reason", "test", "expectedVersion", 0, "items", items))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUPPLY_ACCOUNT_REQUEST"));
        verify(supplyService, never()).createInputSet(any());
    }

    @Test
    void rejectsDeclaredJsonBodyOverOneMiBBeforeBindingWithStableEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/input-limit-probe")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_LENGTH, JSON_BODY_LIMIT + 1)
                        .content("{}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_BODY_TOO_LARGE"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void rejectsUnknownLengthAndChunkedJsonBodiesOverOneMiBButAcceptsExactBoundary() throws Exception {
        byte[] exact = jsonStringBody(JSON_BODY_LIMIT);
        byte[] oversized = jsonStringBody(JSON_BODY_LIMIT + 1);

        mockMvc.perform(post("/api/v1/input-limit-probe")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exact))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/input-limit-probe")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.removeHeader(HttpHeaders.CONTENT_LENGTH);
                            return request;
                        })
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_BODY_TOO_LARGE"));

        mockMvc.perform(post("/api/v1/input-limit-probe")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .with(request -> {
                            request.removeHeader(HttpHeaders.CONTENT_LENGTH);
                            return request;
                        })
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_BODY_TOO_LARGE"));
    }

    @Test
    void doesNotApplyJsonBodyLimitToHealthOrStaticPaths() throws Exception {
        byte[] oversized = jsonStringBody(JSON_BODY_LIMIT + 1);

        mockMvc.perform(post("/actuator/health/task5-probe")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/index.html")
                        .principal(() -> "input-limit-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isNotFound());
    }

    private byte[] productionBody(String area, Map<String, String> quality, Map<String, String> costs)
            throws Exception {
        return productionBody(area, quality, costs, "CORN");
    }

    private byte[] productionBodyWithProductCode(String productCode) throws Exception {
        return productionBody("1.0000", Map.of(), Map.of(), productCode);
    }

    private byte[] productionBody(String area, Map<String, String> quality, Map<String, String> costs,
            String productCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productCode", productCode);
        body.put("objectTypeCode", "FARMER");
        body.put("regionCode", "230200");
        body.put("surveyDate", "2026-08-01");
        body.put("cultivatedAreaMu", area);
        body.put("yieldPerMuKilograms", "1.0000");
        body.put("quality", quality);
        body.put("costs", costs);
        body.put("insurance", Map.of());
        body.put("subsidies", Map.of());
        return objectMapper.writeValueAsBytes(body);
    }

    private static Map<String, String> entries(String prefix, int count, String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) result.put(prefix + index, value);
        return result;
    }

    private static byte[] jsonStringBody(int byteLength) {
        return ("\"" + "a".repeat(byteLength - 2) + "\"").getBytes(StandardCharsets.UTF_8);
    }

    @RestController
    static class JsonProbeController {
        @PostMapping("/api/v1/input-limit-probe")
        ApiResponse<Integer> accept(@RequestBody String body) {
            return new ApiResponse<>(body.length());
        }
    }
}
