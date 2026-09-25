package com.cofco.qiqihar.graintrade.marketintelligence;

import java.util.Arrays;

public enum WorldBankMonthlySeries {
    MAIZE("maize", "Maize", "国际玉米月均价", "美元/吨", "($/mt)"),
    WHEAT_SRW("wheat-srw", "Wheat, US SRW", "美国软红冬小麦月均价", "美元/吨", "($/mt)"),
    RICE_THAI_5("rice-thai-5", "Rice, Thai 5%", "泰国5%碎米月均价", "美元/吨", "($/mt)"),
    SOYBEANS("soybeans", "Soybeans", "国际大豆月均价", "美元/吨", "($/mt)"),
    PALM_OIL("palm-oil", "Palm oil", "国际棕榈油月均价", "美元/吨", "($/mt)"),
    CRUDE_OIL("crude-oil", "Crude oil, average", "国际原油月均价", "美元/桶", "($/bbl)"),
    UREA("urea", "Urea", "国际尿素月均价", "美元/吨", "($/mt)");

    public final String code;
    public final String sourceHeader;
    public final String title;
    public final String unit;
    public final String sourceUnit;

    WorldBankMonthlySeries(String code, String sourceHeader, String title, String unit, String sourceUnit) {
        this.code = code;
        this.sourceHeader = sourceHeader;
        this.title = title;
        this.unit = unit;
        this.sourceUnit = sourceUnit;
    }

    public static WorldBankMonthlySeries fromCode(String code) {
        return Arrays.stream(values()).filter(series -> series.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported World Bank series: " + code));
    }
}
