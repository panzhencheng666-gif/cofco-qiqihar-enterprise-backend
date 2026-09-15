package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

final class RegionalIndicatorAllocation {
    private static final Set<String> TOTAL_UNITS = Set.of("万亩","亩","公顷","万公顷","万吨","吨","万头","万只","亿元","万元","万人","万千瓦","万台","台套","家");
    private RegionalIndicatorAllocation() {}

    static List<RegionalAgricultureProfile.Indicator> allocate(List<RegionalAgricultureProfile.Indicator> values,
            BigDecimal share, String weights) {
        return values.stream().map(i -> {
            // Certification, policy targets and transport flows are not additive over administrative subdivisions.
            boolean contextual = share == null || Set.of("BRAND","LOGISTICS").contains(i.category())
                    || Set.of("PLAN","CONTEXT").contains(i.dataKind()) || !TOTAL_UNITS.contains(i.unit());
            if (contextual) return new RegionalAgricultureProfile.Indicator(i.category(),"上级参考·"+i.label(),i.value(),i.unit(),
                    i.dataYear(),"CONTEXT","参考范围：所属地市。此指标不能仅凭面积推出本地事实；保留上级背景，避免将认证资格、跨区货运或比率按面积当作本地统计。\n原始依据："+i.method(),
                    i.sourceName(),i.sourceUrl(),i.verifiedAt());
            var value = i.value().multiply(share).setScale(4,RoundingMode.HALF_UP);
            String method = "原始依据："+i.dataYear()+"年"+i.sourceName()+"，"+i.label()+"="+i.value()+i.unit()+"（"+i.sourceUrl()+"）。\n"
                    +"选择理由：本级缺少独立资料，采用上级同一指标作为总量约束；不把上级值直接当成本地值。\n"
                    +"分配权重："+weights+"；逐级权重相乘="+share.setScale(8,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"。\n"
                    +"代入计算："+i.value()+"×"+share.setScale(8,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"="+value+i.unit()+"。\n"
                    +"适用假设：在没有更细耕地或经营分布证据时，按边界密度或同级等份分配；这里是情景估算，并非当地实际统计。\n"
                    +"更新规则：上级数据、地区边界或分配依据变化后重新计算。上级计算过程："+i.method();
            return new RegionalAgricultureProfile.Indicator(i.category(),i.label(),value,i.unit(),i.dataYear(),"ESTIMATED",method,
                    i.sourceName(),i.sourceUrl(),i.verifiedAt());
        }).toList();
    }
}
