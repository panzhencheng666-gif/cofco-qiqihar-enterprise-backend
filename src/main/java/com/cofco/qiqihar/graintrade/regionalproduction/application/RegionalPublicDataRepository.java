package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface RegionalPublicDataRepository {
    Context load(String rootRegionCode, int requestedYear);

    List<RegionalAgricultureProfile.Indicator> history(String rootRegionCode, int requestedYear);

    List<RegionalAgricultureProfile.Indicator> calculationHistory(String rootRegionCode, int requestedYear);

    List<DueSource> due(Instant now);

    void recordPageSuccess(String sourceId, Instant fetchedAt, String hash, String excerpt);

    void recordCropMetrics(String sourceId, List<PublicCropMetric> metrics, Instant fetchedAt);

    void recordIndicators(String sourceId, List<RegionalPublicIndicatorParser.Metric> metrics, Instant fetchedAt);

    void recordWeatherSuccess(
            String sourceId, String rootRegionCode, Instant observedAt,
            BigDecimal temperature, BigDecimal precipitation, BigDecimal soilMoisturePercent,
            String risk, String assessment, Instant fetchedAt, String hash, String excerpt);

    void recordFailure(String sourceId, Instant attemptedAt, String message);

    record Context(
            List<RegionalAgricultureProfileCalculator.Observation> observations,
            RegionalAgricultureProfile.RefreshStatus refreshStatus,
            RegionalAgricultureProfile.Weather weather,
            List<RegionalAgricultureProfile.Indicator> indicators,
            List<RegionalAgricultureProfile.Policy> policies,
            List<RegionalAgricultureProfile.Source> sources) {}

    record DueSource(
            String id, String rootRegionCode, String type, String name,
            String url, String parserKey) {}

    record PublicCropMetric(
            int year, String productCode, BigDecimal plantedAreaMu,
            BigDecimal yieldPerMuKg, BigDecimal totalOutputKg, String evidence) {}
}
