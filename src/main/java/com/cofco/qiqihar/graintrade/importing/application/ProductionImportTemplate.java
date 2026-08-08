package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;

public final class ProductionImportTemplate {
    public static final String DOMAIN = "PRODUCTION";
    public static final List<String> SUBMISSION_METADATA_HEADERS = List.of(
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE");
    public static final List<String> HEADERS = List.of("productCode", "objectTypeCode", "regionCode", "cultivarCode",
            "surveyDate", "cultivatedAreaMu", "yieldPerMuKilograms",
            "PROD_REPORTER_NAME", "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT",
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE");
    private ProductionImportTemplate() {}
    public static String csv() { return String.join(",", HEADERS) + "\n"; }
}
