package com.cofco.qiqihar.graintrade.overview.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OverviewBusinessTable(
        String code,
        String title,
        String coverageStatus,
        List<Column> columns,
        List<Row> rows) {

    public OverviewBusinessTable {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public record Column(String code, String label, String unitCode) {}

    public record Cell(String value, long sourceCount) {}

    public record Row(
            String regionCode,
            String regionName,
            long sourceCount,
            String latestApprovedAt,
            String completenessStatus,
            Map<String, Cell> values) {

        public Row {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
