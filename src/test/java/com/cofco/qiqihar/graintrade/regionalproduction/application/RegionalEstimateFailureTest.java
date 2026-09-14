package com.cofco.qiqihar.graintrade.regionalproduction.application;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RegionalEstimateFailureTest {
    @Test void failedCalculationRetainsOldTimeAndRecordsNewAttemptInsteadOfUnchanged() {
        var sources=mock(RegionalPublicDataRepository.class);
        var store=mock(RegionalEstimateBatchRepository.class);
        var old=new RegionalEstimateBatch("230200",2026,"2026-09-13T00:30:00Z","2026-09-13T00:30:00Z",null,"RECALCULATED_CHANGED","PARTIAL","v1",List.of());
        when(sources.history(anyString(),anyInt())).thenThrow(new IllegalStateException("source query failed"));
        when(store.latest("230200",2026)).thenReturn(Optional.of(old));
        var now=Instant.parse("2026-09-14T00:30:00Z");
        new RegionalEstimateBatchService(sources,store).refresh(now);
        var values=ArgumentCaptor.forClass(RegionalEstimateBatch.class);
        verify(store,times(4)).save(values.capture(),anyString());
        var saved=values.getAllValues().getFirst();
        assertThat(saved.calculationStatus()).isEqualTo("FAILED_RETAINED");
        assertThat(saved.calculatedAt()).isEqualTo(old.calculatedAt());
        assertThat(saved.attemptedAt()).isEqualTo(now.toString());
    }
    @Test void partialEngineFailuresCannotBeReportedAsCompleteSearch() {
        assertThat(RegionalSourceDiscovery.searchState(17,2)).isEqualTo("SEARCH_PARTIAL");
        assertThat(RegionalSourceDiscovery.searchState(0,2)).isEqualTo("SEARCH_FAILED");
        assertThat(RegionalSourceDiscovery.searchState(0,0)).isEqualTo("SEARCH_SUCCESS");
    }
    @org.junit.jupiter.api.Test void discoverySeparatesReportPeriodFromCurrentEvents() {
        var queries=RegionalSourceDiscovery.discoveryQueries("大兴安岭",2026);
        org.assertj.core.api.Assertions.assertThat(queries).hasSize(7)
            .contains("大兴安岭 2025年 国民经济和社会发展统计公报", "大兴安岭 2023年 国民经济和社会发展统计公报")
            .anyMatch(q -> q.contains("2026 农业 政策"));
    }

    @org.junit.jupiter.api.Test void discoveryDoesNotPromoteCountyStatisticsToTheirParentCity() {
        org.assertj.core.api.Assertions.assertThat(RegionalSourceDiscovery.isRootAnnualReport("2025年黑河市国民经济和社会发展统计公报", "黑河")).isTrue();
        org.assertj.core.api.Assertions.assertThat(RegionalSourceDiscovery.isRootAnnualReport("2025年黑河市孙吴县国民经济和社会发展统计公报", "黑河")).isFalse();
        org.assertj.core.api.Assertions.assertThat(RegionalSourceDiscovery.isRootAnnualReport("2025年大兴安岭地区国民经济和社会发展统计公报", "大兴安岭")).isTrue();
    }

}
