package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;

/** Server-owned fixed columns for market monitoring imports. */
public final class MarketImportTemplate {
    public static final String DOMAIN = "MARKET";
    public static final List<String> HEADERS = List.of(
            "productCode", "objectTypeCode", "regionCode", "tradeDate", "tradeDirection",
            "purchaseBasePrice", "saleBasePrice", "carriageBoardAmount", "packagingAmount",
            "freightAmount", "packagingForm", "reporterName", "reporterPhone", "sampleName",
            "sampleContact", "latitude", "longitude", "purchaseVolume", "moisture", "evidencePhotoId");

    private MarketImportTemplate() {}

    public static String csv() { return String.join(",", HEADERS) + "\n"; }
}
