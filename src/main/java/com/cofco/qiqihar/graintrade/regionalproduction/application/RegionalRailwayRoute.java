package com.cofco.qiqihar.graintrade.regionalproduction.application;

public record RegionalRailwayRoute(
        String id,
        String name,
        String geometryGeoJson,
        String usage,
        String operator,
        String sourceUrl) {}
