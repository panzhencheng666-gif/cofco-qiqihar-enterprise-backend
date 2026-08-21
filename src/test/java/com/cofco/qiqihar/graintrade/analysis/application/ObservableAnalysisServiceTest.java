package com.cofco.qiqihar.graintrade.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.analysis.domain.AnalysisQualityState;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableQuantityInput;
import com.cofco.qiqihar.graintrade.analysis.domain.ObservableSupplyCalculator;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObservableAnalysisServiceTest {
    private static final ObservableAnalysisScope SCOPE =
            new ObservableAnalysisScope("CORN", "230200", 2026, 8, null, null);
    private static final OffsetDateTime CUTOFF =
            OffsetDateTime.of(2026, 8, 16, 12, 0, 0, 0, ZoneOffset.ofHours(8));

    @Test
    void delegatesOneAuthorizedScopeToTheApprovedFactRepository() {
        ObservableAnalysisRepository repository = mock(ObservableAnalysisRepository.class);
        AccessControl accessControl = mock(AccessControl.class);
        AuthorizedReadScope readScope = new AuthorizedReadScope("employee-1", Set.of("230200"));
        ObservableAnalysisSnapshot expected = snapshot(SCOPE, "OBSERVABLE_ANALYSIS_V1", 3);
        when(accessControl.requireReadScope()).thenReturn(readScope);
        when(repository.knownProduct("CORN")).thenReturn(true);
        when(repository.knownRegion("230200")).thenReturn(true);
        when(repository.canNavigateRegion("230200", readScope.regionCodes())).thenReturn(true);
        when(repository.load(SCOPE, readScope.regionCodes())).thenReturn(expected);

        ObservableAnalysisSnapshot actual = new ObservableAnalysisService(repository, accessControl)
                .snapshot("CORN", "230200", 2026, 8, null, null);

        assertThat(actual).isSameAs(expected);
        verify(repository).load(SCOPE, Set.of("230200"));
    }

    @Test
    void rejectsAnUnauthorizedRegionBeforeReadingFacts() {
        ObservableAnalysisRepository repository = mock(ObservableAnalysisRepository.class);
        AccessControl accessControl = mock(AccessControl.class);
        AuthorizedReadScope readScope = new AuthorizedReadScope("employee-1", Set.of("230200"));
        when(accessControl.requireReadScope()).thenReturn(readScope);
        when(repository.knownProduct("CORN")).thenReturn(true);
        when(repository.knownRegion("150700")).thenReturn(true);
        when(repository.canNavigateRegion("150700", readScope.regionCodes())).thenReturn(false);

        assertThatThrownBy(() -> new ObservableAnalysisService(repository, accessControl)
                .snapshot("CORN", "150700", 2026, 8, null, null))
                .hasMessageContaining("assigned scope");
    }

    @Test
    void rejectsInvalidYearsMonthsAndUnknownOptionalDimensions() {
        ObservableAnalysisRepository repository = mock(ObservableAnalysisRepository.class);
        AccessControl accessControl = mock(AccessControl.class);
        when(repository.knownProduct("CORN")).thenReturn(true);
        when(repository.knownRegion("230200")).thenReturn(true);
        when(repository.knownCultivar("CORN", "CORN-UNKNOWN")).thenReturn(false);
        when(repository.knownSubjectType("PRODUCTION", "FARMER-UNKNOWN")).thenReturn(false);
        ObservableAnalysisService service = new ObservableAnalysisService(repository, accessControl);

        assertThatThrownBy(() -> service.snapshot("CORN", "230200", 1899, 8, null, null))
                .isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(() -> service.snapshot("CORN", "230200", 2026, 13, null, null))
                .isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(() -> service.snapshot(
                "CORN", "230200", 2026, 8, "CORN-UNKNOWN", null))
                .isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(() -> service.snapshot(
                "CORN", "230200", 2026, 8, null, "FARMER-UNKNOWN"))
                .isInstanceOf(ClientRequestException.class);
    }

    @Test
    void keepsTheAnalysisVersionStableForTheSameScopeFactsAndMethodology() {
        ObservableAnalysisSnapshot first = snapshot(SCOPE, "OBSERVABLE_ANALYSIS_V1", 3);
        ObservableAnalysisSnapshot regenerated = snapshot(SCOPE, "OBSERVABLE_ANALYSIS_V1", 3);

        assertThat(first.analysisVersion()).isEqualTo(regenerated.analysisVersion());
        assertThat(first.production()).isNotNull();
        assertThat(first.market()).isNotNull();
        assertThat(first.logistics()).isNotNull();
        assertThat(first.supply()).isNotNull();
    }

    @Test
    void changesTheAnalysisVersionWhenAFactVersionOrMethodologyChanges() {
        ObservableAnalysisSnapshot baseline = snapshot(SCOPE, "OBSERVABLE_ANALYSIS_V1", 3);
        ObservableAnalysisSnapshot changedFact = snapshot(SCOPE, "OBSERVABLE_ANALYSIS_V1", 4);
        ObservableAnalysisSnapshot changedMethod = snapshot(SCOPE, "OBSERVABLE_ANALYSIS_V2", 3);

        assertThat(changedFact.analysisVersion()).isNotEqualTo(baseline.analysisVersion());
        assertThat(changedMethod.analysisVersion()).isNotEqualTo(baseline.analysisVersion());
    }

    private static ObservableAnalysisSnapshot snapshot(
            ObservableAnalysisScope scope, String methodologyVersion, long recordVersion) {
        ObservableMetric output = new ObservableMetric(
                "EXPECTED_OUTPUT", "预计总产", "50.0000", "吨", "SUM", 1, null);
        return ObservableAnalysisSnapshot.create(
                scope,
                methodologyVersion,
                CUTOFF,
                CUTOFF.plusMinutes(1),
                AnalysisQualityState.AVAILABLE,
                List.of(),
                List.of(),
                new AnalysisCoverage(1, 1, 1, 0, 0),
                new ProductionAnalysisView(List.of(output), List.of()),
                new MarketAnalysisView(List.of()),
                new LogisticsAnalysisView(List.of()),
                new ObservableSupplyView(ObservableSupplyCalculator.calculate(
                        new ObservableQuantityInput(
                                decimal("10"), decimal("50"), decimal("5"),
                                decimal("5"), decimal("15"), decimal("25"),
                                true, true, 0, 1)),
                        new ObservableInventoryBreakdown(
                                decimal("10"), null, decimal("25"), null,
                                false, false, 1, 0,
                                null, null, null, null)),
                List.of(new AnalysisLineage(
                        "PRODUCTION", "record-1", recordVersion, List.of("EXPECTED_OUTPUT"),
                        "农户样本1", "齐齐哈尔市", "2026年8月", CUTOFF)));
    }

    private static java.math.BigDecimal decimal(String value) {
        return new java.math.BigDecimal(value);
    }
}
