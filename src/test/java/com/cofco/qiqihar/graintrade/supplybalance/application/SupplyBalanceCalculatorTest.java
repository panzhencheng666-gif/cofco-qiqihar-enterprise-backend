package com.cofco.qiqihar.graintrade.supplybalance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.supplybalance.application.SupplyBalanceCalculator.RegionalProductionSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupplyBalanceCalculatorTest {
    private final SupplyBalanceCalculator calculator = new SupplyBalanceCalculator();

    @Test
    void fixesDistinctManualContractsForAllThreeProducts() {
        assertThat(SupplyBalanceProductContract.forProduct("CORN").manualCodes()).containsExactlyInAnyOrder(
                "OPENING_INVENTORY", "RESERVE_AUCTION_SALES", "EXTERNAL_INFLOW", "IMPORTS",
                "DEEP_PROCESSING", "FEED_USE", "FOOD_SEED_LOSS", "RESERVE_AUCTION_BUYS",
                "RAIL_OUTFLOW", "ROAD_OUTFLOW", "RESERVE_PURCHASE");
        assertThat(SupplyBalanceProductContract.forProduct("SOYBEAN").manualCodes())
                .contains("CRUSH_USE", "PROTEIN_PROCESSING", "INFLOW")
                .doesNotContain("FEED_USE", "OTHER_USE");
        assertThat(SupplyBalanceProductContract.forProduct("RICE").manualCodes())
                .containsExactlyInAnyOrder("OPENING_INVENTORY", "FOOD_USE", "OTHER_USE",
                        "POLICY_RESERVE", "RAIL_OUTFLOW", "ROAD_OUTFLOW");
    }

    @Test
    void convertsRegionalProductionAndCalculatesCornTotalsAndRatios() {
        Map<String, BigDecimal> manual = decimals(Map.ofEntries(
                Map.entry("OPENING_INVENTORY", "10"), Map.entry("RESERVE_AUCTION_SALES", "2"),
                Map.entry("EXTERNAL_INFLOW", "3"), Map.entry("IMPORTS", "4"),
                Map.entry("DEEP_PROCESSING", "5"), Map.entry("FEED_USE", "6"),
                Map.entry("FOOD_SEED_LOSS", "1"), Map.entry("RESERVE_AUCTION_BUYS", "2"),
                Map.entry("RAIL_OUTFLOW", "1"), Map.entry("ROAD_OUTFLOW", "2"),
                Map.entry("RESERVE_PURCHASE", "1")));

        var rows = calculator.calculate("CORN", source("150000", "500", "10000000"), manual, Map.of());
        var view = new SupplyBalanceView("230200", "齐齐哈尔市", "PREFECTURE", 2026,
                "CORN", true, 0, null, rows);

        assertThat(view.value("PLANTED_AREA")).isEqualByComparingTo("1");
        assertThat(view.value("YIELD")).isEqualByComparingTo("7.5");
        assertThat(view.value("OUTPUT")).isEqualByComparingTo("1");
        assertThat(view.value("TOTAL_SUPPLY")).isEqualByComparingTo("20");
        assertThat(view.value("TOTAL_DEMAND")).isEqualByComparingTo("18");
        assertThat(view.value("CLOSING_INVENTORY")).isEqualByComparingTo("2");
        assertThat(view.value("DEMAND_SUPPLY_RATIO")).isEqualByComparingTo("90");
    }

    @Test
    void keepsMissingProductionExplicitAndAllowsDerivedNegativeClosingInventory() {
        var missing = calculator.calculate("RICE", null, Map.of(), Map.of());
        assertThat(missing.stream().filter(row -> row.code().equals("OUTPUT")).findFirst().orElseThrow().display())
                .isEqualTo("缺少地区产情数据");

        Map<String, BigDecimal> manual = decimals(Map.of(
                "OPENING_INVENTORY", "0", "FOOD_USE", "2", "OTHER_USE", "0",
                "POLICY_RESERVE", "0", "RAIL_OUTFLOW", "0", "ROAD_OUTFLOW", "0"));
        var rows = calculator.calculate("RICE", source("1", "1", "10000000"), manual, Map.of());
        var closing = rows.stream().filter(row -> row.code().equals("CLOSING_INVENTORY"))
                .findFirst().orElseThrow();
        assertThat(closing.value()).isEqualByComparingTo("-1");
    }

    @Test
    void exposesReportedAreaBeforeYieldAndOutputAreAvailable() {
        var rows = calculator.calculate("CORN",
                new RegionalProductionSource(new BigDecimal("3414800"), null, null),
                Map.of(), Map.of());
        var view = new SupplyBalanceView("150722", "莫力达瓦达斡尔族自治旗", "COUNTY", 2026,
                "CORN", true, 0, null, rows);

        assertThat(view.value("PLANTED_AREA")).isEqualByComparingTo("22.765333");
        assertThat(view.value("YIELD")).isNull();
        assertThat(view.value("OUTPUT")).isNull();
        assertThat(rows.stream().filter(row -> row.code().equals("YIELD"))
                .findFirst().orElseThrow().display()).isEqualTo("缺少地区产情数据");
    }

    @Test
    void displaysZeroSupplyRatiosAsNotCalculable() {
        Map<String, BigDecimal> zero = new LinkedHashMap<>();
        SupplyBalanceProductContract.forProduct("RICE").manualCodes()
                .forEach(code -> zero.put(code, BigDecimal.ZERO));
        var rows = calculator.calculate("RICE", source("0", "0", "0"), zero, Map.of());
        assertThat(rows.stream().filter(row -> row.code().equals("DEMAND_SUPPLY_RATIO"))
                .findFirst().orElseThrow().display()).isEqualTo("不可计算");
    }

    private static RegionalProductionSource source(String area, String yield, String output) {
        return new RegionalProductionSource(new BigDecimal(area), new BigDecimal(yield), new BigDecimal(output));
    }

    private static Map<String, BigDecimal> decimals(Map<String, String> source) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        source.forEach((code, value) -> values.put(code, new BigDecimal(value)));
        return values;
    }
}
