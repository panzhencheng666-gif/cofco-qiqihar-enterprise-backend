package com.cofco.qiqihar.graintrade.overview.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RegionSurplusCalculator {
    public static final String CALCULATION_VERSION = "REGION_SURPLUS_V1";
    private static final Comparator<RegionSurplusSource> LATEST_FIRST = Comparator
            .comparing(RegionSurplusSource::approvedAt, Comparator.reverseOrder())
            .thenComparing(RegionSurplusSource::sourceVersion, Comparator.reverseOrder())
            .thenComparing(RegionSurplusSource::sourceRecordId, Comparator.reverseOrder());

    public RegionSurplusCalculation calculate(List<RegionSurplusSource> suppliedSources) {
        List<RegionSurplusSource> sources = suppliedSources == null ? List.of() : List.copyOf(suppliedSources);
        if (sources.isEmpty()) return unavailable("NO_APPROVED_SOURCES", sources, null);

        if (sources.stream().anyMatch(source -> contractIssue(source) != null)
                || sources.stream().map(RegionSurplusSource::sourceRecordId).distinct().count() != sources.size()) {
            return unavailable("UNRELIABLE_SOURCE_CONTRACT", sources, commonCutoff(sources));
        }

        Set<LocalDate> cutoffs = sources.stream().map(RegionSurplusSource::dataCutoff)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (cutoffs.size() != 1) return unavailable("CUTOFF_MISMATCH", sources, null);
        LocalDate cutoff = cutoffs.iterator().next();

        boolean productionPresent = sources.stream().anyMatch(source -> source.sourceDomain().equals("PRODUCTION"));
        boolean marketPresent = sources.stream().anyMatch(source -> source.sourceDomain().equals("MARKET"));
        if (!productionPresent || !marketPresent) {
            return unavailable("INSUFFICIENT_COVERAGE", sources, cutoff);
        }

        Set<String> productionSubjects = sources.stream()
                .filter(source -> source.sourceDomain().equals("PRODUCTION"))
                .map(RegionSurplusSource::subjectKey).collect(Collectors.toSet());
        boolean overlaps = sources.stream().filter(source -> source.sourceDomain().equals("MARKET"))
                .map(RegionSurplusSource::cargoOwnerKey).anyMatch(productionSubjects::contains);
        if (overlaps) return unavailable("MUTUAL_EXCLUSIVITY_VIOLATION", sources, cutoff);

        Map<String, Decision> decisions = new LinkedHashMap<>();
        adoptLatestProductionSources(sources, decisions);
        adoptEnterpriseSources(sources, decisions);

        List<RegionSurplusAuditSource> auditSources = sources.stream()
                .sorted(Comparator.comparing(RegionSurplusSource::sourceDomain)
                        .thenComparing(RegionSurplusSource::sourceRecordId))
                .map(source -> audit(source, decisions.get(source.sourceRecordId())))
                .toList();
        List<RegionSurplusSource> adopted = sources.stream()
                .filter(source -> decisions.get(source.sourceRecordId()).adopted()).toList();
        BigDecimal total = adopted.stream().map(RegionSurplusSource::valueTonnes)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RegionSurplusCalculation(
                total, adopted.size(), cutoff, "AVAILABLE", CALCULATION_VERSION, auditSources);
    }

    private static void adoptLatestProductionSources(
            List<RegionSurplusSource> sources, Map<String, Decision> decisions) {
        groupBy(sources.stream().filter(source -> source.sourceDomain().equals("PRODUCTION")).toList(),
                RegionSurplusSource::subjectKey).values().forEach(group -> {
                    List<RegionSurplusSource> ranked = group.stream().sorted(LATEST_FIRST).toList();
                    decisions.put(ranked.getFirst().sourceRecordId(),
                            new Decision(true, "LATEST_APPROVED_SOURCE"));
                    ranked.stream().skip(1).forEach(source -> decisions.put(source.sourceRecordId(),
                            new Decision(false, "SUPERSEDED_BY_LATEST_APPROVAL")));
                });
    }

    private static void adoptEnterpriseSources(
            List<RegionSurplusSource> sources, Map<String, Decision> decisions) {
        List<RegionSurplusSource> market = sources.stream()
                .filter(source -> source.sourceDomain().equals("MARKET")).toList();
        Map<String, List<RegionSurplusSource>> revisions = groupBy(market, source -> String.join("|",
                source.inventoryHolderKey(), source.cargoOwnerKey(), source.regionCode(),
                source.dataCutoff().toString(), source.ownershipType()));
        List<RegionSurplusSource> latestLots = new ArrayList<>();
        revisions.values().forEach(group -> {
            List<RegionSurplusSource> ranked = group.stream().sorted(LATEST_FIRST).toList();
            latestLots.add(ranked.getFirst());
            ranked.stream().skip(1).forEach(source -> decisions.put(source.sourceRecordId(),
                    new Decision(false, "SUPERSEDED_BY_LATEST_APPROVAL")));
        });

        groupBy(latestLots, source -> String.join("|", source.cargoOwnerKey(),
                source.regionCode(), source.dataCutoff().toString())).values().forEach(ownerLots -> {
                    List<RegionSurplusSource> owned = ownerLots.stream()
                            .filter(source -> source.ownershipType().equals("OWNED"))
                            .sorted(LATEST_FIRST).toList();
                    if (!owned.isEmpty()) {
                        RegionSurplusSource adopted = owned.getFirst();
                        decisions.put(adopted.sourceRecordId(), new Decision(true, "OWNER_REPORTED_SOURCE"));
                        ownerLots.stream().filter(source -> source != adopted).forEach(source -> decisions.put(
                                source.sourceRecordId(), new Decision(false, "OWNER_REPORTED_SOURCE_HAS_PRIORITY")));
                    } else {
                        List<RegionSurplusSource> ranked = ownerLots.stream().sorted(LATEST_FIRST).toList();
                        decisions.put(ranked.getFirst().sourceRecordId(),
                                new Decision(true, "LATEST_CUSTODIAL_SOURCE"));
                        ranked.stream().skip(1).forEach(source -> decisions.put(source.sourceRecordId(),
                                new Decision(false, "SUPERSEDED_BY_LATEST_CUSTODIAL_SOURCE")));
                    }
                });
    }

    private static String contractIssue(RegionSurplusSource source) {
        if (source == null) return "SOURCE_MISSING";
        if (!blank(source.contractIssue())) return source.contractIssue();
        if (blank(source.sourceDomain()) || blank(source.sourceRecordId()) || source.sourceVersion() < 0
                || blank(source.subjectKey()) || blank(source.cargoOwnerKey()) || blank(source.ownershipType())
                || blank(source.regionCode()) || source.dataCutoff() == null || source.valueTonnes() == null
                || source.valueTonnes().signum() < 0 || source.approvedAt() == null) return "REQUIRED_FIELD_MISSING";
        if (source.sourceDomain().equals("PRODUCTION")) {
            return source.inventoryHolderKey() == null
                    && source.ownershipType().equals("PRODUCTION_SURPLUS")
                    && source.subjectKey().equals(source.cargoOwnerKey()) ? null : "INVALID_PRODUCTION_OWNERSHIP";
        }
        if (!source.sourceDomain().equals("MARKET") || blank(source.inventoryHolderKey())) {
            return "INVALID_SOURCE_DOMAIN";
        }
        if (source.ownershipType().equals("OWNED")) {
            return source.inventoryHolderKey().equals(source.cargoOwnerKey())
                    ? null : "OWNED_INVENTORY_OWNER_MISMATCH";
        }
        if (source.ownershipType().equals("CUSTODIAL")) {
            return source.inventoryHolderKey().equals(source.cargoOwnerKey())
                    ? "CUSTODIAL_INVENTORY_OWNER_MISMATCH" : null;
        }
        return "UNKNOWN_OWNERSHIP_TYPE";
    }

    private static RegionSurplusCalculation unavailable(
            String status, List<RegionSurplusSource> sources, LocalDate cutoff) {
        List<RegionSurplusAuditSource> audit = sources.stream().filter(java.util.Objects::nonNull)
                .map(source -> audit(source, new Decision(false,
                        contractIssue(source) == null ? status : contractIssue(source))))
                .toList();
        return new RegionSurplusCalculation(null, 0, cutoff, status, CALCULATION_VERSION, audit);
    }

    private static LocalDate commonCutoff(List<RegionSurplusSource> sources) {
        Set<LocalDate> cutoffs = sources.stream().filter(java.util.Objects::nonNull)
                .map(RegionSurplusSource::dataCutoff).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return cutoffs.size() == 1 ? cutoffs.iterator().next() : null;
    }

    private static RegionSurplusAuditSource audit(RegionSurplusSource source, Decision decision) {
        return new RegionSurplusAuditSource(
                source.sourceDomain(), source.sourceRecordId(), source.sourceVersion(), source.subjectKey(),
                source.inventoryHolderKey(), source.cargoOwnerKey(), source.ownershipType(), source.regionCode(),
                source.dataCutoff(), source.valueTonnes(), source.approvedAt(), decision.adopted(), decision.reason());
    }

    private static <K> Map<K, List<RegionSurplusSource>> groupBy(
            List<RegionSurplusSource> sources, Function<RegionSurplusSource, K> classifier) {
        return sources.stream().collect(Collectors.groupingBy(
                classifier, LinkedHashMap::new, Collectors.toList()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Decision(boolean adopted, String reason) {}
}
