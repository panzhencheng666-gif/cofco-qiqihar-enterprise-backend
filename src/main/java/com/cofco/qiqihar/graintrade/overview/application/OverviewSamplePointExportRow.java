package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;
import java.util.UUID;

public record OverviewSamplePointExportRow(
        UUID samplePointId,
        String name,
        String regionCode,
        String regionName,
        List<String> categories,
        List<String> types,
        List<String> products,
        List<String> contacts,
        double longitude,
        double latitude) {
    public OverviewSamplePointExportRow {
        categories = List.copyOf(categories);
        types = List.copyOf(types);
        products = List.copyOf(products);
        contacts = List.copyOf(contacts);
    }
}
