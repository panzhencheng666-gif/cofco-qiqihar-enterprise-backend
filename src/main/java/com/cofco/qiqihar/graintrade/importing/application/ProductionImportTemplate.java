package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;

public final class ProductionImportTemplate {
    public static final String DOMAIN = "PRODUCTION";
    public static final List<String> HEADERS = List.of("productCode", "objectTypeCode", "regionCode", "cultivarCode",
            "surveyDate", "cultivatedAreaMu", "yieldPerMuKilograms");
    private ProductionImportTemplate() {}
    public static String csv() { return String.join(",", HEADERS) + "\n"; }
}
