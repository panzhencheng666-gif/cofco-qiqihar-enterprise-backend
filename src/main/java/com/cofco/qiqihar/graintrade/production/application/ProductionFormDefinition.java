package com.cofco.qiqihar.graintrade.production.application;

import java.util.List;

public record ProductionFormDefinition(
        String productCode, String objectTypeCode, String contractVersion, String contractDigest,
        List<ProductionSurveyField> fields, List<ProductionFactGroup> groups) {
    public ProductionFormDefinition {
        fields = List.copyOf(fields);
        groups = List.copyOf(groups);
    }

    public ProductionFormDefinition(String productCode, String objectTypeCode, String contractVersion,
            List<ProductionSurveyField> fields, List<ProductionFactGroup> groups) {
        this(productCode, objectTypeCode, contractVersion, ProductionSurveyFieldContract.DIGEST, fields, groups);
    }
}
