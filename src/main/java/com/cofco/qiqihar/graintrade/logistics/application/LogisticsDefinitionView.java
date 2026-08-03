package com.cofco.qiqihar.graintrade.logistics.application;

import java.util.List;

public record LogisticsDefinitionView(
        String productCode,
        List<Field> fields,
        List<Action> actions) {
    public record Field(String code, String label, String controlType, String unit,
                        Integer precision, Integer scale, boolean required, boolean readOnly,
                        int sortOrder, List<Option> options) {}
    public record Option(String value, String label, int sortOrder) {}
    public record Action(String code, String label, String scope, int sortOrder) {}
}
