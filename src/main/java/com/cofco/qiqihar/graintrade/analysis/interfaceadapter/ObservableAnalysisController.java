package com.cofco.qiqihar.graintrade.analysis.interfaceadapter;

import com.cofco.qiqihar.graintrade.analysis.application.AnalysisCoverage;
import com.cofco.qiqihar.graintrade.analysis.application.AnalysisLineage;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisScope;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisService;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableAnalysisSnapshot;
import com.cofco.qiqihar.graintrade.analysis.application.ObservableMetric;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculation;
import com.cofco.qiqihar.graintrade.analysis.domain.ProductionSourceBalance;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObservableAnalysisController {
    private static final Set<String> ALLOWED_QUERY_PARAMETERS = Set.of(
            "productCode",
            "regionCode",
            "surveyYear",
            "surveyMonth",
            "cultivarCode",
            "subjectTypeCode");

    private final ObservableAnalysisService service;

    public ObservableAnalysisController(ObservableAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/observable-analysis/snapshots")
    ApiResponse<SnapshotResponse> snapshot(
            @RequestParam MultiValueMap<String, String> parameters) {
        StrictQueryParameters parsed = StrictQueryParameters.parse(
                parameters,
                ALLOWED_QUERY_PARAMETERS::contains,
                ObservableAnalysisController::invalidQuery);
        int surveyYear = integer(parsed.required("surveyYear"));
        Integer surveyMonth = parsed.optional("surveyMonth") == null
                ? null
                : integer(parsed.optional("surveyMonth"));
        if (surveyYear < 1900 || surveyYear > 2200
                || (surveyMonth != null && (surveyMonth < 1 || surveyMonth > 12))) {
            throw invalidQuery();
        }
        ObservableAnalysisSnapshot snapshot = service.snapshot(
                parsed.required("productCode").trim(),
                parsed.required("regionCode").trim(),
                surveyYear,
                surveyMonth,
                optional(parsed.optional("cultivarCode")),
                optional(parsed.optional("subjectTypeCode")));
        return new ApiResponse<>(SnapshotResponse.from(snapshot));
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidQuery();
        }
    }

    private static String optional(String value) {
        return value == null ? null : value.trim();
    }

    private static ClientRequestException invalidQuery() {
        return new ClientRequestException(
                "INVALID_OBSERVABLE_ANALYSIS_QUERY",
                "Observable analysis query context is invalid");
    }

    record SnapshotResponse(
            ScopeResponse scope,
            String analysisVersion,
            String methodologyVersion,
            OffsetDateTime dataCutoffAt,
            OffsetDateTime generatedAt,
            String qualityState,
            List<String> blockingReasons,
            List<String> warnings,
            AnalysisCoverage coverage,
            ProductionResponse production,
            MetricsResponse market,
            MetricsResponse logistics,
            SupplyResponse supply,
            List<LineageResponse> lineage) {

        static SnapshotResponse from(ObservableAnalysisSnapshot snapshot) {
            return new SnapshotResponse(
                    ScopeResponse.from(snapshot.scope()),
                    snapshot.analysisVersion(),
                    snapshot.methodologyVersion(),
                    snapshot.dataCutoffAt(),
                    snapshot.generatedAt(),
                    snapshot.qualityState().name(),
                    snapshot.blockingReasons(),
                    snapshot.warnings(),
                    snapshot.coverage(),
                    new ProductionResponse(
                            snapshot.production().metrics(),
                            snapshot.production().sourceBalances().stream()
                                    .map(SourceBalanceResponse::from)
                                    .toList()),
                    new MetricsResponse(snapshot.market().metrics()),
                    new MetricsResponse(snapshot.logistics().metrics()),
                    new SupplyResponse(CalculationResponse.from(
                            snapshot.supply().calculation())),
                    snapshot.lineage().stream().map(LineageResponse::from).toList());
        }
    }

    record ScopeResponse(
            String productCode,
            String regionCode,
            int surveyYear,
            Integer surveyMonth,
            String cultivarCode,
            String subjectTypeCode) {
        static ScopeResponse from(ObservableAnalysisScope scope) {
            return new ScopeResponse(
                    scope.productCode(),
                    scope.regionCode(),
                    scope.surveyYear(),
                    scope.surveyMonth(),
                    scope.cultivarCode(),
                    scope.subjectTypeCode());
        }
    }

    record ProductionResponse(
            List<ObservableMetric> metrics,
            List<SourceBalanceResponse> sourceBalances) { }

    record MetricsResponse(List<ObservableMetric> metrics) { }

    record SupplyResponse(CalculationResponse calculation) { }

    record CalculationResponse(
            String qualityState,
            String openingObservableInventoryTonnes,
            String expectedOutputTonnes,
            String inflowTonnes,
            String selfUseTonnes,
            String outflowTonnes,
            String endingObservableInventoryTonnes,
            String inferredOtherAbsorptionTonnes,
            List<String> issues) {
        static CalculationResponse from(ObservableSupplyCalculation calculation) {
            return new CalculationResponse(
                    calculation.qualityState().name(),
                    decimal(calculation.openingObservableInventoryTonnes()),
                    decimal(calculation.expectedOutputTonnes()),
                    decimal(calculation.inflowTonnes()),
                    decimal(calculation.selfUseTonnes()),
                    decimal(calculation.outflowTonnes()),
                    decimal(calculation.endingObservableInventoryTonnes()),
                    decimal(calculation.inferredOtherAbsorptionTonnes()),
                    calculation.issues());
        }
    }

    record SourceBalanceResponse(
            String qualityState,
            String estimatedOutputTonnes,
            String productionAvailableTonnes,
            String knownDestinationTonnes,
            String theoreticalEndingInventoryTonnes,
            String reportedEndingInventoryTonnes,
            String reconciliationDifferenceTonnes,
            List<String> issues) {
        static SourceBalanceResponse from(ProductionSourceBalance balance) {
            return new SourceBalanceResponse(
                    balance.qualityState().name(),
                    decimal(balance.estimatedOutputTonnes()),
                    decimal(balance.productionAvailableTonnes()),
                    decimal(balance.knownDestinationTonnes()),
                    decimal(balance.theoreticalEndingInventoryTonnes()),
                    decimal(balance.reportedEndingInventoryTonnes()),
                    decimal(balance.reconciliationDifferenceTonnes()),
                    balance.issues());
        }
    }

    record LineageResponse(
            String sourceDomain,
            List<String> factCodes,
            String subjectLabel,
            String regionLabel,
            String periodLabel,
            OffsetDateTime approvedAt) {
        static LineageResponse from(AnalysisLineage lineage) {
            return new LineageResponse(
                    lineage.sourceDomain(),
                    lineage.factCodes(),
                    lineage.subjectLabel(),
                    lineage.regionLabel(),
                    lineage.periodLabel(),
                    lineage.approvedAt());
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
