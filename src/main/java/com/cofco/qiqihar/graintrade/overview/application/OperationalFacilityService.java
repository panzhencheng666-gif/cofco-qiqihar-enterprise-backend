package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.regionalproduction.api.RegionalRailwayCatalogue;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
        var railwayRegionCodes = storage.railwayRegionCodes(regionCode);
        var railwayResults = railwayRegionCodes.stream()
                .map(railways::findFacilities).filter(value -> value.boundaryAvailable()).toList();
        var categories = List.of(
                category("OWNED", "自有库点", storageFacilities),
                category("LEASED", "租赁库点", storageFacilities),
                category("HISTORICAL_LEASED", "历史租赁库点", storageFacilities));
        var facilityById = new LinkedHashMap<String, OperationalFacilityCatalogue.RailwayFacility>();
        railwayResults.forEach(railway -> railway.facilities().forEach(value -> facilityById.putIfAbsent(
                value.sourceId(), new OperationalFacilityCatalogue.RailwayFacility(
                        value.sourceId(), value.name(), value.kind(), value.longitude(), value.latitude(),
                        value.operator(), value.reference(), value.status(), value.service(), value.locationRelation(),
                        value.distanceKm(), value.nearbyLines(), value.sourceUrl()))));
        var facilities = facilityById.values().stream()
                .sorted(Comparator.comparing(OperationalFacilityCatalogue.RailwayFacility::locationRelation)
                        .reversed().thenComparing(OperationalFacilityCatalogue.RailwayFacility::name)
                        .thenComparing(OperationalFacilityCatalogue.RailwayFacility::sourceId))
                .toList();
        var lineByName = new LinkedHashMap<String, OperationalFacilityCatalogue.RailwayLine>();
        railwayResults.forEach(railway -> railway.lines().forEach(value -> lineByName.putIfAbsent(
                value.name(), new OperationalFacilityCatalogue.RailwayLine(
                        value.name(), value.mappedTrackKm(), value.usage(), value.electrification(),
                        value.gauge(), value.operator(), value.sourceUrl()))));
        var lines = lineByName.values().stream()
                .sorted(Comparator.comparing(OperationalFacilityCatalogue.RailwayLine::name)).toList();
        var routes = railwayRegionCodes.stream().flatMap(code -> railways.findRoutes(code).stream())
                .map(value -> new OperationalFacilityCatalogue.RailwayRoute(
                        value.id(), value.name(), value.geometryGeoJson(), value.usage(), value.operator(),
                        value.sourceUrl()))
                .sorted(Comparator.comparing(OperationalFacilityCatalogue.RailwayRoute::name)
                        .thenComparing(OperationalFacilityCatalogue.RailwayRoute::id))
                .toList();
        String railwaySourceAsOf = railwayResults.stream().map(value -> value.sourceAsOf())
                .filter(value -> value != null && !value.isBlank()).max(String::compareTo).orElse(null);
        String storageAsOf = storage.latestSourceAsOf(regionCode);
        return new OperationalFacilityCatalogue(
                regionCode, productCode, effectiveAsOf, categories, storageFacilities, facilities, lines, routes,
                List.of(
                        new OperationalFacilityCatalogue.SourceStatus(
                                "STORAGE", "关联库点", sourceStatus(storageAsOf, 90), storageAsOf, null,
                                "关系、仓容和价格仅采用保留证据的内部核定或公开记录；空值表示尚未核定。"),
                        new OperationalFacilityCatalogue.SourceStatus(
                                "RAILWAY", "铁路站点", sourceStatus(railwaySourceAsOf, 30), railwaySourceAsOf,
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
