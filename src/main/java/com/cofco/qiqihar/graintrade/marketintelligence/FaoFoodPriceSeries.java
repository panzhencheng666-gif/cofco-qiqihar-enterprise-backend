package com.cofco.qiqihar.graintrade.marketintelligence;

import java.util.Arrays;

public enum FaoFoodPriceSeries {
    FOOD("food", "FAO 食品价格指数"),
    MEAT("meat", "FAO 肉类价格指数"),
    DAIRY("dairy", "FAO 乳制品价格指数"),
    CEREALS("cereals", "FAO 谷物价格指数"),
    OILS("oils", "FAO 植物油价格指数"),
    SUGAR("sugar", "FAO 食糖价格指数");

    public final String code;
    public final String title;

    FaoFoodPriceSeries(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public static FaoFoodPriceSeries fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown FAO food price series"));
    }
}
