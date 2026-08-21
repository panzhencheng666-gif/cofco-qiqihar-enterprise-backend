package com.cofco.qiqihar.graintrade.overview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionSurplusCalculatorTest {
    private static final LocalDate CUTOFF = LocalDate.of(2026, 8, 10);

    @Test
    void failsClosedWhenNoUniqueCalculationContractWasSelected() {
        RegionSurplusCalculation result = new RegionSurplusCalculator().calculate(List.of(), null);

        assertThat(result.coverageStatus()).isEqualTo("CALCULATION_CONTRACT_UNAVAILABLE");
        assertThat(result.valueTonnes()).isNull();
        assertThat(result.calculationVersion()).isEqualTo("地区余粮口径不可用");
    }

    @Test
    void adoptsTheLatestProductionSourceAndPrefersOwnerReportedInventoryOverCustodyDuplicates() {
        RegionSurplusCalculation result = new RegionSurplusCalculator().calculate(List.of(
                production("production-old", 2, "farmer-1", "10", "2026-08-10T08:00:00+08:00"),
                production("production-latest", 4, "farmer-1", "12", "2026-08-10T10:00:00+08:00"),
                enterprise("market-custody-duplicate", 3, "depot-1", "owner-1", "CUSTODIAL", "20",
                        "2026-08-10T09:00:00+08:00"),
                enterprise("market-owner", 5, "owner-1", "owner-1", "OWNED", "20",
                        "2026-08-10T11:00:00+08:00"),
                enterprise("market-custody-distinct", 2, "depot-2", "owner-2", "CUSTODIAL", "5",
                        "2026-08-10T12:00:00+08:00")));

        assertThat(result.coverageStatus()).isEqualTo("AVAILABLE");
        assertThat(result.valueTonnes()).isEqualByComparingTo("37");
        assertThat(result.sourceCount()).isEqualTo(3);
        assertThat(result.dataCutoff()).isEqualTo(CUTOFF);
        assertThat(result.calculationVersion()).isEqualTo("地区余粮口径第1版");
        assertThat(result.auditSources()).filteredOn(RegionSurplusAuditSource::adopted)
                .extracting(RegionSurplusAuditSource::sourceRecordId)
                .containsExactlyInAnyOrder("production-latest", "market-owner", "market-custody-distinct");
        assertThat(result.auditSources()).filteredOn(source -> !source.adopted())
                .extracting(RegionSurplusAuditSource::adoptionReason)
                .contains("SUPERSEDED_BY_LATEST_APPROVAL", "OWNER_REPORTED_SOURCE_HAS_PRIORITY");
    }

    @Test
    void failsClosedWhenCoverageIsIncompleteOrTheCutoffIsInconsistent() {
        RegionSurplusCalculation incomplete = new RegionSurplusCalculator().calculate(List.of(
                production("production-only", 2, "farmer-1", "12", "2026-08-10T10:00:00+08:00")));
        assertThat(incomplete.coverageStatus()).isEqualTo("INSUFFICIENT_COVERAGE");
        assertThat(incomplete.valueTonnes()).isNull();

        RegionSurplusCalculation inconsistent = new RegionSurplusCalculator().calculate(List.of(
                production("production", 2, "farmer-1", "12", "2026-08-10T10:00:00+08:00"),
                enterprise("market", 2, "owner-1", "owner-1", "OWNED", "20",
                        "2026-08-11T10:00:00+08:00", LocalDate.of(2026, 8, 11))));
        assertThat(inconsistent.coverageStatus()).isEqualTo("CUTOFF_MISMATCH");
        assertThat(inconsistent.valueTonnes()).isNull();
    }

    @Test
    void failsClosedWhenIdentityCannotBeReliablyDeduplicatedOrDomainsOverlap() {
        RegionSurplusCalculation missingIdentity = new RegionSurplusCalculator().calculate(List.of(
                production("production", 2, null, "12", "2026-08-10T10:00:00+08:00"),
                enterprise("market", 2, "owner-1", "owner-1", "OWNED", "20",
                        "2026-08-10T11:00:00+08:00")));
        assertThat(missingIdentity.coverageStatus()).isEqualTo("UNRELIABLE_SOURCE_CONTRACT");
        assertThat(missingIdentity.valueTonnes()).isNull();

        RegionSurplusCalculation overlappingDomains = new RegionSurplusCalculator().calculate(List.of(
                production("production", 2, "same-owner", "12", "2026-08-10T10:00:00+08:00"),
                enterprise("market", 2, "same-owner", "same-owner", "OWNED", "20",
                        "2026-08-10T11:00:00+08:00")));
        assertThat(overlappingDomains.coverageStatus()).isEqualTo("MUTUAL_EXCLUSIVITY_VIOLATION");
        assertThat(overlappingDomains.valueTonnes()).isNull();
    }

    @Test
    void adoptsOnlyTheLatestCustodialReportForTheSameCargoOwner() {
        RegionSurplusCalculation result = new RegionSurplusCalculator().calculate(List.of(
                production("production", 2, "farmer-1", "12", "2026-08-10T10:00:00+08:00"),
                enterprise("market-custody-old", 2, "depot-1", "owner-1", "CUSTODIAL", "20",
                        "2026-08-10T09:00:00+08:00"),
                enterprise("market-custody-latest", 3, "depot-2", "owner-1", "CUSTODIAL", "20",
                        "2026-08-10T11:00:00+08:00")));

        assertThat(result.coverageStatus()).isEqualTo("AVAILABLE");
        assertThat(result.valueTonnes()).isEqualByComparingTo("32");
        assertThat(result.auditSources()).filteredOn(RegionSurplusAuditSource::adopted)
                .extracting(RegionSurplusAuditSource::sourceRecordId)
                .containsExactlyInAnyOrder("production", "market-custody-latest");
        assertThat(result.auditSources()).filteredOn(source -> !source.adopted())
                .extracting(RegionSurplusAuditSource::adoptionReason)
                .contains("SUPERSEDED_BY_LATEST_CUSTODIAL_SOURCE");
    }

    @Test
    void reconcilesTheSameInventorySnapshotAcrossLegacyAndCurrentContracts() {
        List<RegionSurplusSource> legacySnapshot = List.of(
                production("production", 2, "legacy-farmer-1", "12", "2026-08-10T10:00:00+08:00"),
                enterprise("market", 2, "owner-1", "owner-1", "OWNED", "20",
                        "2026-08-10T11:00:00+08:00"));
        List<RegionSurplusSource> currentSnapshot = List.of(
                new RegionSurplusSource("PRODUCTION", "production", 2, "sample-point-1", null,
                        "sample-point-1", "PRODUCTION_SURPLUS", "230208101001", CUTOFF,
                        new BigDecimal("12"), OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
                        null, "地区余粮口径第2版", "FARMER"),
                new RegionSurplusSource("MARKET", "market", 2, "owner-1", "owner-1", "owner-1",
                        "OWNED", "230208101001", CUTOFF, new BigDecimal("20"),
                        OffsetDateTime.parse("2026-08-10T11:00:00+08:00"), null,
                        "地区余粮口径第2版", "TRADER"));

        RegionSurplusCalculation legacy = new RegionSurplusCalculator().calculate(legacySnapshot);
        RegionSurplusCalculation current = new RegionSurplusCalculator().calculate(currentSnapshot);

        assertThat(legacy.valueTonnes()).isEqualByComparingTo(current.valueTonnes());
        assertThat(legacy.sourceCount()).isEqualTo(current.sourceCount()).isEqualTo(2);
        assertThat(legacy.coverageStatus()).isEqualTo(current.coverageStatus()).isEqualTo("AVAILABLE");
        assertThat(legacy.calculationVersion()).isEqualTo("地区余粮口径第1版");
        assertThat(current.calculationVersion()).isEqualTo("地区余粮口径第2版");
        assertThat(legacy.auditSources()).extracting(RegionSurplusAuditSource::subjectKey)
                .contains("legacy-farmer-1");
        assertThat(current.auditSources()).extracting(RegionSurplusAuditSource::subjectKey)
                .contains("sample-point-1");
    }

    private static RegionSurplusSource production(
            String id, long version, String subject, String value, String approvedAt) {
        return new RegionSurplusSource("PRODUCTION", id, version, subject, null, subject,
                "PRODUCTION_SURPLUS", "230208101001", CUTOFF, new BigDecimal(value),
                OffsetDateTime.parse(approvedAt), null, "地区余粮口径第1版", "FARMER");
    }

    private static RegionSurplusSource enterprise(
            String id, long version, String holder, String owner, String ownership,
            String value, String approvedAt) {
        return enterprise(id, version, holder, owner, ownership, value, approvedAt, CUTOFF);
    }

    private static RegionSurplusSource enterprise(
            String id, long version, String holder, String owner, String ownership,
            String value, String approvedAt, LocalDate cutoff) {
        return new RegionSurplusSource("MARKET", id, version, holder, holder, owner, ownership,
                "230208101001", cutoff, new BigDecimal(value), OffsetDateTime.parse(approvedAt), null,
                "地区余粮口径第1版", "TRADER");
    }
}
