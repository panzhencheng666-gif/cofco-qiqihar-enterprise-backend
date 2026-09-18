package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.regionalproduction.api.RegionalRailwayCatalogue;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalFacilityService {
    private final OperationalStorageFacilityRepository storage;
    private final RegionalRailwayCatalogue railways;

    public OperationalFacilityService(
            OperationalStorageFacilityRepository storage,
            RegionalRailwayCatalogue railways) {
        this.storage = storage;
        this.railways = railways;
    }

    @Transactional(readOnly = true)
    public OperationalFacilityCatalogue find(String regionCode, String productCode, LocalDate asOf) {
        LocalDate effectiveAsOf = asOf == null ? LocalDate.now() : asOf;
        var storageFacilities = storage.find(regionCode, productCode, effectiveAsOf);
        var railway = railways.find(regionCode);
        var categories = List.of(
                category("OWNED", "自有库点", storageFacilities),
                category("LEASED", "租赁库点", storageFacilities),
                category("HISTORICAL_LEASED", "历史租赁库点", storageFacilities));
        var facilities = railway.facilities().stream().map(value ->
                new OperationalFacilityCatalogue.RailwayFacility(
                        value.sourceId(), value.name(), value.kind(), value.longitude(), value.latitude(),
                        value.operator(), value.reference(), value.status(), value.service(), value.locationRelation(),
                        value.distanceKm(), value.nearbyLines(), value.sourceUrl())).toList();
        var lines = railway.lines().stream().map(value ->
                new OperationalFacilityCatalogue.RailwayLine(
                        value.name(), value.mappedTrackKm(), value.usage(), value.electrification(),
                        value.gauge(), value.operator(), value.sourceUrl())).toList();
        String storageAsOf = storage.latestSourceAsOf(regionCode);
        return new OperationalFacilityCatalogue(
                regionCode, productCode, effectiveAsOf, categories, storageFacilities, facilities, lines,
                List.of(
                        new OperationalFacilityCatalogue.SourceStatus(
                                "STORAGE", "关联库点", sourceStatus(storageAsOf, 90), storageAsOf, null,
                                "关系、仓容和价格仅采用保留证据的内部核定或公开记录；空值表示尚未核定。"),
                        new OperationalFacilityCatalogue.SourceStatus(
                                "RAILWAY", "铁路站点", sourceStatus(railway.sourceAsOf(), 30), railway.sourceAsOf(),
                                "https://www.openstreetmap.org/copyright",
                                "OpenStreetMap 地理参考不等于铁路货运营业资质，业务能力需另行核验。")));
    }

    private static OperationalFacilityCatalogue.Category category(
            String code, String label, List<OperationalFacilityCatalogue.StorageFacility> facilities) {
        long count = facilities.stream().filter(value -> code.equals(value.relationType())).count();
        return new OperationalFacilityCatalogue.Category(code, label, count);
    }

    private static String sourceStatus(String value, long readyDays) {
        if (value == null || value.isBlank()) return "UNAVAILABLE";
        try {
            Instant instant = value.length() == 10
                    ? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
                    : Instant.parse(value);
            return Duration.between(instant, Instant.now()).toDays() <= readyDays ? "READY" : "STALE";
        } catch (RuntimeException exception) {
            return "STALE";
        }
    }
}
