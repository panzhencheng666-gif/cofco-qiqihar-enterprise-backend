package com.cofco.qiqihar.graintrade.analysis.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class InventoryPositionSelector {
    private static final int SCALE = 4;

    private InventoryPositionSelector() { }

    public static InventoryPositionSelection select(
            List<InventoryPositionObservation> observations) {
        if (observations == null) {
            throw new IllegalArgumentException("Inventory position observations are required");
        }
        Map<String, List<InventoryPositionObservation>> groups = observations.stream()
                .collect(Collectors.groupingBy(
                        InventoryPositionObservation::positionKey,
                        LinkedHashMap::new,
                        Collectors.toList()));
        BigDecimal total = null;
        int reviewGroups = 0;
        Set<String> adopted = new LinkedHashSet<>();
        Comparator<InventoryPositionObservation> order = Comparator
                .comparingLong(InventoryPositionObservation::version)
                .thenComparing(InventoryPositionObservation::approvedAt)
                .thenComparing(InventoryPositionObservation::recordId);

        for (List<InventoryPositionObservation> group : groups.values()) {
            LocalDate latestDate = group.stream()
                    .map(InventoryPositionObservation::observedOn)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            List<InventoryPositionObservation> latest = group.stream()
                    .filter(item -> item.observedOn().equals(latestDate))
                    .toList();
            long valueCount = latest.stream()
                    .map(item -> item.valueTonnes().stripTrailingZeros().toPlainString())
                    .distinct()
                    .count();
            if (valueCount > 1) {
                reviewGroups++;
                continue;
            }
            InventoryPositionObservation winner = latest.stream().max(order).orElseThrow();
            total = total == null ? winner.valueTonnes() : total.add(winner.valueTonnes());
            adopted.add(winner.recordId());
        }

        List<InventoryPositionObservation> adoptedObservations = observations.stream()
                .filter(item -> adopted.contains(item.recordId()))
                .toList();
        reviewGroups += SimilarInventoryPositions.warningKeys(adoptedObservations).size();
        BigDecimal normalized = total == null ? null : total.setScale(SCALE, RoundingMode.HALF_UP);
        LocalDate earliestObservedOn = adoptedObservations.stream()
                .map(InventoryPositionObservation::observedOn)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDate latestObservedOn = adoptedObservations.stream()
                .map(InventoryPositionObservation::observedOn)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new InventoryPositionSelection(
                normalized, adopted.size(), reviewGroups, adopted,
                earliestObservedOn, latestObservedOn);
    }

    private static final class SimilarInventoryPositions {
        private static final BigDecimal NEARBY_DEGREES = new BigDecimal("0.0001");

        private static Set<String> warningKeys(
                List<InventoryPositionObservation> observations) {
            Set<String> warnings = new LinkedHashSet<>();
            Map<String, List<InventoryPositionObservation>> bySubject = observations.stream()
                    .collect(Collectors.groupingBy(InventoryPositionObservation::subjectKey));
            bySubject.forEach((subject, group) -> addNearbyWarning(warnings, subject, group));
            addVisibleIdentityWarnings(warnings, observations);
            return warnings;
        }

        private static void addNearbyWarning(
                Set<String> warnings,
                String subject,
                List<InventoryPositionObservation> group) {
            for (int left = 0; left < group.size(); left++) {
                for (int right = left + 1; right < group.size(); right++) {
                    InventoryPositionObservation first = group.get(left);
                    InventoryPositionObservation second = group.get(right);
                    if (nearbyButNotExact(first, second)) {
                        warnings.add("NEAR|" + subject);
                    }
                }
            }
        }

        private static boolean nearbyButNotExact(
                InventoryPositionObservation first,
                InventoryPositionObservation second) {
            return first.regionCode().equals(second.regionCode())
                    && first.latitude() != null && second.latitude() != null
                    && !first.positionKey().equals(second.positionKey())
                    && first.latitude().subtract(second.latitude()).abs()
                            .compareTo(NEARBY_DEGREES) <= 0
                    && first.longitude().subtract(second.longitude()).abs()
                            .compareTo(NEARBY_DEGREES) <= 0;
        }

        private static void addVisibleIdentityWarnings(
                Set<String> warnings,
                List<InventoryPositionObservation> observations) {
            observations.stream().collect(Collectors.groupingBy(
                    item -> item.regionCode() + "|CONTACT|" + item.normalizedContact()))
                    .forEach((key, group) -> {
                        if (group.stream().map(InventoryPositionObservation::normalizedName)
                                .distinct().count() > 1) {
                            warnings.add(key);
                        }
                    });
            observations.stream().collect(Collectors.groupingBy(
                    item -> item.regionCode() + "|NAME|" + item.normalizedName()))
                    .forEach((key, group) -> {
                        if (group.stream().map(InventoryPositionObservation::normalizedContact)
                                .distinct().count() > 1) {
                            warnings.add(key);
                        }
                    });
        }
    }
}
