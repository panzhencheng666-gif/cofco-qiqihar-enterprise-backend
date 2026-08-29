package com.cofco.qiqihar.graintrade.supplybalance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SupplyBalanceCalculator {
    private static final BigDecimal MU_PER_WAN_HECTARES = new BigDecimal("150000");
    private static final BigDecimal YIELD_CONVERSION = new BigDecimal("0.015");
    private static final BigDecimal KG_PER_WAN_TONNES = new BigDecimal("10000000");

    public List<SupplyBalanceView.Row> calculate(
            String productCode, RegionalProductionSource source,
            Map<String, BigDecimal> manualValues, Map<String, String> notes) {
        SupplyBalanceProductContract contract = SupplyBalanceProductContract.forProduct(productCode);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (source != null) {
            if (source.plantedAreaMu() != null) {
                values.put("PLANTED_AREA",
                        source.plantedAreaMu().divide(MU_PER_WAN_HECTARES, 6, RoundingMode.HALF_UP));
            }
            if (source.yieldPerMuKg() != null) {
                values.put("YIELD",
                        source.yieldPerMuKg().multiply(YIELD_CONVERSION).setScale(6, RoundingMode.HALF_UP));
            }
            if (source.totalOutputKg() != null) {
                values.put("OUTPUT",
                        source.totalOutputKg().divide(KG_PER_WAN_TONNES, 6, RoundingMode.HALF_UP));
            }
        }
        values.putAll(manualValues);
        derive(productCode, values);
        List<SupplyBalanceView.Row> rows = new ArrayList<>();
        for (SupplyBalanceProductContract.Field field : contract.fields()) {
            BigDecimal value = values.get(field.code());
            String display = value == null
                    ? (field.kind() == SupplyBalanceProductContract.FieldKind.AUTO
                            ? "缺少地区产情数据" : null)
                    : value.stripTrailingZeros().toPlainString();
            if (field.kind() == SupplyBalanceProductContract.FieldKind.RATIO
                    && values.containsKey(field.code()) && value == null) {
                display = "不可计算";
            }
            rows.add(new SupplyBalanceView.Row(field.code(), field.label(), field.kind(), field.unit(),
                    field.requirement(), value, display, notes.get(field.code())));
        }
        return rows;
    }

    private static void derive(String product, Map<String, BigDecimal> values) {
        if ("CORN".equals(product)) {
            values.put("TOTAL_SUPPLY", sum(values, "OUTPUT", "OPENING_INVENTORY", "RESERVE_AUCTION_SALES",
                    "EXTERNAL_INFLOW", "IMPORTS"));
            values.put("REGIONAL_USE", sum(values, "DEEP_PROCESSING", "FEED_USE", "FOOD_SEED_LOSS",
                    "RESERVE_AUCTION_BUYS"));
            values.put("OUTFLOW", sum(values, "RAIL_OUTFLOW", "ROAD_OUTFLOW", "RESERVE_PURCHASE"));
        } else if ("SOYBEAN".equals(product)) {
            values.put("TOTAL_SUPPLY", sum(values, "OUTPUT", "OPENING_INVENTORY", "IMPORTS", "INFLOW"));
            values.put("REGIONAL_USE", sum(values, "FOOD_USE", "CRUSH_USE", "PROTEIN_PROCESSING",
                    "POLICY_RESERVE"));
            values.put("OUTFLOW", sum(values, "RAIL_OUTFLOW", "ROAD_OUTFLOW"));
        } else {
            values.put("TOTAL_SUPPLY", sum(values, "OUTPUT", "OPENING_INVENTORY"));
            values.put("REGIONAL_USE", sum(values, "FOOD_USE", "OTHER_USE", "POLICY_RESERVE"));
            values.put("OUTFLOW", sum(values, "RAIL_OUTFLOW", "ROAD_OUTFLOW"));
        }
        values.put("TOTAL_DEMAND", sum(values, "REGIONAL_USE", "OUTFLOW"));
        values.put("CLOSING_INVENTORY", subtract(values.get("TOTAL_SUPPLY"), values.get("TOTAL_DEMAND")));
        values.put("DEMAND_SUPPLY_RATIO", ratio(values.get("TOTAL_DEMAND"), values.get("TOTAL_SUPPLY")));
        values.put("CLOSING_SUPPLY_RATIO", ratio(values.get("CLOSING_INVENTORY"), values.get("TOTAL_SUPPLY")));
    }

    private static BigDecimal sum(Map<String, BigDecimal> values, String... codes) {
        BigDecimal result = BigDecimal.ZERO;
        for (String code : codes) {
            BigDecimal value = values.get(code);
            if (value == null) return null;
            result = result.add(value);
        }
        return result;
    }

    private static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.subtract(right);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.multiply(new BigDecimal("100")).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    public record RegionalProductionSource(
            BigDecimal plantedAreaMu, BigDecimal yieldPerMuKg, BigDecimal totalOutputKg) {}
}
