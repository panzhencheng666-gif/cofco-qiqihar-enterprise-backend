package com.cofco.qiqihar.graintrade.supplybalance.interfaceadapter;

import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceView;
import java.time.Instant;
import java.util.List;

public record SupplyBalanceResponse(
        String regionCode, String regionName, String administrativeLevel,
        int surveyYear, String productCode, boolean regionalProductionAvailable,
        long version, Instant updatedAt, List<Row> rows) {

    static SupplyBalanceResponse from(SupplyBalanceView value) {
        return new SupplyBalanceResponse(
                value.regionCode(), value.regionName(), value.administrativeLevel(), value.surveyYear(),
                value.productCode(), value.regionalProductionAvailable(), value.version(), value.updatedAt(),
                value.rows().stream().map(row -> new Row(
                        row.code(), row.label(), row.kind().name(), row.unit(), row.requirement(),
                        row.value() == null ? null : row.value().toPlainString(), row.display(), row.note())).toList());
    }

    public record Row(
            String code, String label, String kind, String unit, String requirement,
            String value, String display, String note) {}
}
