package com.cofco.qiqihar.graintrade.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryPositionSelectorTest {

    @Test
    void selectsLatestPerExactPositionWithoutCollapsingDifferentWarehouses() {
        InventoryPositionSelection result = InventoryPositionSelector.select(List.of(
                observation("old", "enterprise|47.3500000|123.9100000", "2026-07-31", 1, "300"),
                observation("new", "enterprise|47.3500000|123.9100000", "2026-08-31", 2, "320"),
                observation("other-warehouse", "enterprise|47.3600000|123.9200000",
                        "2026-08-31", 1, "200")));

        assertThat(result.totalTonnes()).isEqualByComparingTo("520.0000");
        assertThat(result.adoptedRecordIds()).containsExactlyInAnyOrder("new", "other-warehouse");
        assertThat(result.reviewGroupCount()).isZero();
    }

    @Test
    void reportsObservationRangeForAdoptedLatestSnapshots() {
        InventoryPositionSelection result = InventoryPositionSelector.select(List.of(
                observation("spring-old", "enterprise-a|47|123", "2026-03-20", 1, "300"),
                observation("autumn-latest", "enterprise-a|47|123", "2026-11-25", 2, "320"),
                observation("other-latest", "enterprise-b|48|124", "2026-09-10", 1, "200")));

        assertThat(result.totalTonnes()).isEqualByComparingTo("520.0000");
        assertThat(result.earliestObservedOn()).isEqualTo(LocalDate.parse("2026-09-10"));
        assertThat(result.latestObservedOn()).isEqualTo(LocalDate.parse("2026-11-25"));
    }

    @Test
    void countsAnExactDuplicateOnceAndLocalizesDifferentValuesForTheSamePositionAndDate() {
        InventoryPositionSelection result = InventoryPositionSelector.select(List.of(
                observation("duplicate-a", "enterprise-a|47|123", "2026-08-31", 1, "300"),
                observation("duplicate-b", "enterprise-a|47|123", "2026-08-31", 2, "300.0000"),
                observation("conflict-a", "enterprise-b|48|124", "2026-08-31", 1, "100"),
                observation("conflict-b", "enterprise-b|48|124", "2026-08-31", 1, "120"),
                observation("valid", "enterprise-c|49|125", "2026-08-31", 1, "80")));

        assertThat(result.totalTonnes()).isEqualByComparingTo("380.0000");
        assertThat(result.adoptedRecordCount()).isEqualTo(2);
        assertThat(result.reviewGroupCount()).isEqualTo(1);
        assertThat(result.adoptedRecordIds()).contains("duplicate-b", "valid")
                .doesNotContain("conflict-a", "conflict-b");
    }

    @Test
    void warnsButDoesNotMergeSimilarIdentityOrNearbyCoordinates() {
        InventoryPositionSelection result = InventoryPositionSelector.select(List.of(
                observation("near-a", "visible-a", "粮库一", "13800000000", "230221",
                        "47.3500000", "123.9100000", "2026-08-31", 1, "300"),
                observation("near-b", "visible-a", "粮库一", "13800000000", "230221",
                        "47.3500500", "123.9100500", "2026-08-31", 1, "200"),
                observation("name-variant", "visible-b", "粮库壹", "13800000000", "230221",
                        "47.5000000", "123.5000000", "2026-08-31", 1, "100")));

        assertThat(result.totalTonnes()).isEqualByComparingTo("600.0000");
        assertThat(result.adoptedRecordCount()).isEqualTo(3);
        assertThat(result.reviewGroupCount()).isEqualTo(2);
    }

    private static InventoryPositionObservation observation(
            String recordId, String positionKey, String observedOn, long version, String tonnes) {
        String[] parts = positionKey.split("\\|");
        String subjectKey = String.join("|", Arrays.copyOf(parts, parts.length - 2));
        return observation(recordId, subjectKey, subjectKey, subjectKey, "230200",
                parts[parts.length - 2], parts[parts.length - 1], observedOn, version, tonnes);
    }

    private static InventoryPositionObservation observation(
            String recordId, String subjectKey, String normalizedName, String normalizedContact,
            String regionCode, String latitude, String longitude,
            String observedOn, long version, String tonnes) {
        return new InventoryPositionObservation(
                recordId, subjectKey, normalizedName, normalizedContact, regionCode,
                new BigDecimal(latitude), new BigDecimal(longitude), LocalDate.parse(observedOn), version,
                OffsetDateTime.parse("2026-08-11T10:00:00Z").plusSeconds(version),
                new BigDecimal(tonnes));
    }
}
