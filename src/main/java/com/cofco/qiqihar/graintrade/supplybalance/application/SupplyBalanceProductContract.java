package com.cofco.qiqihar.graintrade.supplybalance.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SupplyBalanceProductContract(String productCode, List<Field> fields) {
    private static final Map<String, SupplyBalanceProductContract> CONTRACTS = contracts();

    public static SupplyBalanceProductContract forProduct(String productCode) {
        SupplyBalanceProductContract contract = CONTRACTS.get(productCode);
        if (contract == null) {
            throw new ClientRequestException("SUPPLY_BALANCE_PRODUCT_INVALID", "供需平衡品种无效");
        }
        return contract;
    }

    public Set<String> manualCodes() {
        return fields.stream().filter(field -> field.kind() == FieldKind.MANUAL)
                .map(Field::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> allCodes() {
        return fields.stream().map(Field::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, SupplyBalanceProductContract> contracts() {
        Map<String, SupplyBalanceProductContract> values = new LinkedHashMap<>();
        values.put("CORN", new SupplyBalanceProductContract("CORN", List.of(
                auto("PLANTED_AREA", "播种面积", "万公顷"), auto("YIELD", "单产", "吨/公顷"),
                auto("OUTPUT", "产量", "万吨"), manual("OPENING_INVENTORY", "期初库存"),
                manual("RESERVE_AUCTION_SALES", "储备竞拍销"), manual("EXTERNAL_INFLOW", "区域外流入量"),
                manual("IMPORTS", "进口量"), derived("TOTAL_SUPPLY", "总供给"),
                manual("DEEP_PROCESSING", "深加工消费"), manual("FEED_USE", "饲料消费"),
                manual("FOOD_SEED_LOSS", "食种损"), manual("RESERVE_AUCTION_BUYS", "储备竞价采"),
                derived("REGIONAL_USE", "区域内消费"), manual("RAIL_OUTFLOW", "铁路流出"),
                manual("ROAD_OUTFLOW", "公路流出"), manual("RESERVE_PURCHASE", "储备收购"),
                derived("OUTFLOW", "流出量"), derived("TOTAL_DEMAND", "总需求"),
                derived("CLOSING_INVENTORY", "商业结转库存"), ratio("DEMAND_SUPPLY_RATIO", "需求占供给比"),
                ratio("CLOSING_SUPPLY_RATIO", "结转库存占供给比"))));
        values.put("SOYBEAN", new SupplyBalanceProductContract("SOYBEAN", List.of(
                auto("PLANTED_AREA", "播种面积", "万公顷"), auto("YIELD", "单产", "吨/公顷"),
                auto("OUTPUT", "产量", "万吨"), manual("OPENING_INVENTORY", "期初库存"),
                manual("IMPORTS", "进口量"), manual("INFLOW", "流入量"),
                derived("TOTAL_SUPPLY", "总供给"), manual("FOOD_USE", "食品消费"),
                manual("CRUSH_USE", "大豆压榨"), manual("PROTEIN_PROCESSING", "蛋白加工"),
                manual("POLICY_RESERVE", "其它（政策收储）"), derived("REGIONAL_USE", "区域内消费"),
                manual("RAIL_OUTFLOW", "铁路流出"), manual("ROAD_OUTFLOW", "公路流出"),
                derived("OUTFLOW", "流出量"), derived("TOTAL_DEMAND", "总需求"),
                derived("CLOSING_INVENTORY", "期末库存"), ratio("DEMAND_SUPPLY_RATIO", "需求占供给比"),
                ratio("CLOSING_SUPPLY_RATIO", "期末库存占供给比"))));
        values.put("RICE", new SupplyBalanceProductContract("RICE", List.of(
                auto("PLANTED_AREA", "播种面积", "万公顷"), auto("YIELD", "单产", "吨/公顷"),
                auto("OUTPUT", "产量", "万吨"), manual("OPENING_INVENTORY", "期初库存"),
                derived("TOTAL_SUPPLY", "总供给"), manual("FOOD_USE", "食品消费"),
                manual("OTHER_USE", "其他消费"), manual("POLICY_RESERVE", "政策储备"),
                derived("REGIONAL_USE", "区域内消费"), manual("RAIL_OUTFLOW", "铁路流出"),
                manual("ROAD_OUTFLOW", "公路流出"), derived("OUTFLOW", "流出量"),
                derived("TOTAL_DEMAND", "总需求"), derived("CLOSING_INVENTORY", "期末库存"),
                ratio("DEMAND_SUPPLY_RATIO", "需求占供给比"),
                ratio("CLOSING_SUPPLY_RATIO", "期末库存占供给比"))));
        return Map.copyOf(values);
    }

    private static Field auto(String code, String label, String unit) {
        return new Field(code, label, FieldKind.AUTO, unit, "来自地区年度产情自动换算");
    }

    private static Field manual(String code, String label) {
        return new Field(code, label, FieldKind.MANUAL, "万吨", "按本地区本年度实际口径填报");
    }

    private static Field derived(String code, String label) {
        return new Field(code, label, FieldKind.DERIVED, "万吨", "系统自动计算");
    }

    private static Field ratio(String code, String label) {
        return new Field(code, label, FieldKind.RATIO, "%", "系统自动计算");
    }

    public record Field(String code, String label, FieldKind kind, String unit, String requirement) {}
    public enum FieldKind { AUTO, MANUAL, DERIVED, RATIO }
}
