package com.cofco.qiqihar.graintrade.analysis.interfaceadapter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.analysis.application.AnalysisCoverage;
import com.cofco.qiqihar.graintrade.analysis.application.AnalysisLineage;
import com.cofco.qiqihar.graintrade.analysis.application.LogisticsAnalysisView;
import com.cofco.qiqihar.graintrade.analysis.application.MarketAnalysisView;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisScope;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisService;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisSnapshot;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableMetric;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableSupplyView;
import com.cofco.qiqihar.graintrade.analysis.application.ProductionAnalysisView;
import com.cofco.qiqihar.graintrade.analysis.domain.AnalysisQualityState;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableQuantityInput;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculator;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.testsupport.TestSecurityConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ObservableAnalysisController.class)
@Import(TestSecurityConfiguration.class)
@ContextConfiguration(classes = GrainTradeApplication.class)
@ActiveProfiles("test")
class ObservableAnalysisRestIntegrationTest {
    private static final String ENDPOINT = "/api/v1/observable-analysis/snapshots";
    private static final OffsetDateTime CUTOFF =
            OffsetDateTime.of(2026, 8, 16, 12, 0, 0, 0, ZoneOffset.ofHours(8));

    @Autowired MockMvc mockMvc;
    @MockitoBean ObservableAnalysisService service;
    @MockitoBean AccessControl accessControl;
    @MockitoBean com.cofco.qiqihar.graintrade.logistics.application.CurrentActor logisticsActor;
    @MockitoBean com.cofco.qiqihar.graintrade.market.application.CurrentActor marketActor;
    @MockitoBean com.cofco.qiqihar.graintrade.supply.application.CurrentActor supplyActor;

    @BeforeEach
    void approvedSnapshot() {
        when(service.snapshot("CORN", "230200", 2026, 8, null, null))
                .thenReturn(snapshot());
    }

    @Test
    void returnsOneStableReadOnlySnapshotWithoutPrivateIdentityKeys() throws Exception {
        mockMvc.perform(validGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.productCode").value("CORN"))
                .andExpect(jsonPath("$.data.scope.regionCode").value("230200"))
                .andExpect(jsonPath("$.data.analysisVersion").value(containsString("sha256:")))
                .andExpect(jsonPath("$.data.qualityState").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.production.metrics[0].value").value("50.0000"))
                .andExpect(jsonPath("$.data.market.metrics[0].value").value("2500.0000"))
                .andExpect(jsonPath("$.data.logistics.metrics[0].value").value("5.0000"))
                .andExpect(jsonPath("$.data.supply.calculation.expectedOutputTonnes").value("50.0000"))
                .andExpect(jsonPath("$.data.lineage[0].subjectLabel").value("龙江县调查户"))
                .andExpect(content().string(not(containsString("recordId"))))
                .andExpect(content().string(not(containsString("subjectId"))))
                .andExpect(content().string(not(containsString("subjectCode"))))
                .andExpect(content().string(not(containsString("samplePointId"))))
                .andExpect(content().string(not(containsString("partyId"))));
    }

    @Test
    void rejectsUnknownRepeatedBlankAndIllegalPeriodParameters() throws Exception {
        mockMvc.perform(validGet().queryParam("unexpected", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OBSERVABLE_ANALYSIS_QUERY"));
        mockMvc.perform(get(ENDPOINT)
                        .principal(() -> "analysis-reader")
                        .queryParam("productCode", "CORN", "RICE")
                        .queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(ENDPOINT)
                        .principal(() -> "analysis-reader")
                        .queryParam("productCode", " ")
                        .queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(ENDPOINT)
                        .principal(() -> "analysis-reader")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "230200")
                        .queryParam("surveyYear", "2026")
                        .queryParam("surveyMonth", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OBSERVABLE_ANALYSIS_QUERY"));
    }

    @Test
    void preservesTheEstablishedForbiddenEnvelopeForUnauthorizedRegions() throws Exception {
        when(service.snapshot("CORN", "150700", 2026, 8, null, null))
                .thenThrow(new AccessDeniedException(
                        "ACCESS_REGION_DENIED", "Data region is outside the assigned scope"));

        mockMvc.perform(get(ENDPOINT)
                        .principal(() -> "analysis-reader")
                        .queryParam("productCode", "CORN")
                        .queryParam("regionCode", "150700")
                        .queryParam("surveyYear", "2026")
                        .queryParam("surveyMonth", "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_REGION_DENIED"));
    }

    @Test
    void exposesNoNetworkWriteMethodForObservableAnalysis() throws Exception {
        postPutPatchDelete().forEach(request -> {
            try {
                mockMvc.perform(request)
                        .andExpect(status().isMethodNotAllowed());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validGet() {
        return get(ENDPOINT)
                .principal(() -> "analysis-reader")
                .queryParam("productCode", "CORN")
                .queryParam("regionCode", "230200")
                .queryParam("surveyYear", "2026")
                .queryParam("surveyMonth", "8");
    }

    private List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>
            postPutPatchDelete() {
        return List.of(
                post(ENDPOINT).principal(() -> "analysis-reader")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                put(ENDPOINT).principal(() -> "analysis-reader")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch(ENDPOINT).principal(() -> "analysis-reader")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete(ENDPOINT).principal(() -> "analysis-reader"));
    }

    private static ObservableAnalysisSnapshot snapshot() {
        ObservableAnalysisScope scope =
                new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null);
        return ObservableAnalysisSnapshot.create(
                scope,
                "OBSERVABLE_ANALYSIS_V1",
                CUTOFF,
                CUTOFF.plusMinutes(1),
                AnalysisQualityState.AVAILABLE,
                List.of(),
                List.of("样本覆盖仅代表当前核定范围"),
                new AnalysisCoverage(3, 1, 1, 1),
                new ProductionAnalysisView(
                        List.of(new ObservableMetric(
                                "EXPECTED_OUTPUT", "预计总产", "50.0000", "吨", "SUM", 1, null)),
                        List.of()),
                new MarketAnalysisView(List.of(new ObservableMetric(
                        "AVERAGE_TRADE_PRICE", "平均成交价", "2500.0000", "元/吨", "AVERAGE", 1, null))),
                new LogisticsAnalysisView(List.of(new ObservableMetric(
                        "INFLOW_VOLUME", "确认流入量", "5.0000", "吨", "SUM", 1, null))),
                new ObservableSupplyView(ObservableSupplyCalculator.calculate(
                        new ObservableQuantityInput(
                                decimal("10"), decimal("50"), decimal("5"), decimal("5"),
                                decimal("15"), decimal("25"), true, 3))),
                List.of(new AnalysisLineage(
                        "PRODUCTION", "private-record-key", 7,
                        List.of("EXPECTED_OUTPUT"), "龙江县调查户", "龙江县", "2026年8月", CUTOFF)));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
