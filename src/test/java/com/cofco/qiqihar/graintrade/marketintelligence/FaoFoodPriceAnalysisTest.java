package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaoFoodPriceAnalysisTest {
    @Test
    void calculatesPublishedPeriodChangesAndDoesNotBridgeAMissingMonth() {
        var feed = mock(FaoFoodPriceFeed.class);
        var latest = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        var rows = new ArrayList<FaoFoodPriceFeed.Point>();
        for (int i = 12; i >= 0; i--)
            rows.add(new FaoFoodPriceFeed.Point(latest.minusMonths(i),
                    BigDecimal.valueOf(100 + (12 - i)), FaoFoodPriceFeed.SOURCE_URL));
        when(feed.points(FaoFoodPriceSeries.FOOD, 13)).thenReturn(rows);
        when(feed.state()).thenReturn(new FaoFoodPriceFeed.State(Instant.now(), Instant.now(), latest, null));

        var snapshot = new FaoFoodPriceAnalysis(feed).snapshot("food", 12);
        assertThat(snapshot.latest()).isEqualByComparingTo("112");
        assertThat(snapshot.monthChangePct()).isEqualByComparingTo("0.90");
        assertThat(snapshot.yearChangePct()).isEqualByComparingTo("12.00");
        assertThat(snapshot.trailingThreeMonthAverage()).isEqualByComparingTo("111.00");
        assertThat(snapshot.points()).hasSize(12);
        assertThat(snapshot.stale()).isFalse();

        when(feed.points(FaoFoodPriceSeries.FOOD, 13)).thenReturn(List.of(rows.get(10), rows.get(12)));
        var gap = new FaoFoodPriceAnalysis(feed).snapshot("food", 12);
        assertThat(gap.monthChangePct()).isNull();
        assertThat(gap.trend()).isEqualTo("INSUFFICIENT_DATA");
    }
}
