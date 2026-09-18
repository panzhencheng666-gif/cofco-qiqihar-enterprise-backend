package com.cofco.qiqihar.graintrade.regionalproduction.api;

import com.cofco.qiqihar.graintrade.regionalproduction.application.RegionalRailwayRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RegionalRailwayCatalogue {
    private final RegionalRailwayRepository repository;

    public RegionalRailwayCatalogue(RegionalRailwayRepository repository) {
        this.repository = repository;
    }

    public Result find(String regionCode) {
        var result = repository.find(regionCode);
        return new Result(
                result.regionCode(), result.boundaryAvailable(), result.sourceAsOf(),
                result.facilities().stream().map(value -> new Facility(
                        value.sourceId(), value.name(), value.kind(), value.longitude(), value.latitude(),
                        value.operator(), value.reference(), value.status(), value.service(), value.locationRelation(),
                        value.distanceKm(), value.nearbyLines(), value.sourceUrl())).toList(),
                result.lines().stream().map(value -> new Line(
                        value.name(), value.mappedTrackKm(), value.usage(), value.electrification(),
                        value.gauge(), value.operator(), value.sourceUrl())).toList());
    }

    public record Result(
            String regionCode, boolean boundaryAvailable, String sourceAsOf,
            List<Facility> facilities, List<Line> lines) {}

    public record Facility(
            String sourceId, String name, String kind, BigDecimal longitude, BigDecimal latitude,
            String operator, String reference, String status, String service, String locationRelation,
            BigDecimal distanceKm, String nearbyLines, String sourceUrl) {}

    public record Line(
            String name, BigDecimal mappedTrackKm, String usage, String electrification,
            String gauge, String operator, String sourceUrl) {}
}
