package com.cofco.qiqihar.graintrade.analysis.application;

import com.cofco.qiqihar.graintrade.analysis.domain.AnalysisQualityState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

public record ObservableAnalysisSnapshot(
        ObservableAnalysisScope scope,
        String analysisVersion,
        String methodologyVersion,
        OffsetDateTime dataCutoffAt,
        OffsetDateTime generatedAt,
        AnalysisQualityState qualityState,
        List<String> blockingReasons,
        List<String> warnings,
        AnalysisCoverage coverage,
        ProductionAnalysisView production,
        MarketAnalysisView market,
        LogisticsAnalysisView logistics,
        ObservableSupplyView supply,
        List<AnalysisLineage> lineage) {

    public ObservableAnalysisSnapshot {
        if (scope == null || blank(analysisVersion) || blank(methodologyVersion)
                || dataCutoffAt == null || generatedAt == null || qualityState == null
                || blockingReasons == null || warnings == null || coverage == null
                || production == null || market == null || logistics == null || supply == null
                || lineage == null) {
            throw new IllegalArgumentException("Observable analysis snapshot is incomplete");
        }
        blockingReasons = List.copyOf(blockingReasons);
        warnings = List.copyOf(warnings);
        lineage = List.copyOf(lineage);
    }

    public static ObservableAnalysisSnapshot create(
            ObservableAnalysisScope scope,
            String methodologyVersion,
            OffsetDateTime dataCutoffAt,
            OffsetDateTime generatedAt,
            AnalysisQualityState qualityState,
            List<String> blockingReasons,
            List<String> warnings,
            AnalysisCoverage coverage,
            ProductionAnalysisView production,
            MarketAnalysisView market,
            LogisticsAnalysisView logistics,
            ObservableSupplyView supply,
            List<AnalysisLineage> lineage) {
        if (scope == null || blank(methodologyVersion) || dataCutoffAt == null || lineage == null) {
            throw new IllegalArgumentException("Analysis version inputs are incomplete");
        }
        String factVersions = lineage.stream().map(AnalysisLineage::canonicalKey).sorted()
                .collect(java.util.stream.Collectors.joining("|"));
        String version = digest(String.join("|",
                scope.canonicalKey(), methodologyVersion, dataCutoffAt.toInstant().toString(), factVersions));
        return new ObservableAnalysisSnapshot(
                scope, version, methodologyVersion, dataCutoffAt, generatedAt, qualityState,
                blockingReasons, warnings, coverage, production, market, logistics, supply, lineage);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
