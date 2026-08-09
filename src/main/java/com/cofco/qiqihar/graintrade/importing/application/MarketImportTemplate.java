package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;

/** Server-owned fixed columns for market monitoring imports. */
public final class MarketImportTemplate {
    public static final String DOMAIN = "MARKET";
    public static final List<String> HEADERS = List.of(
            "productCode", "objectTypeCode", "regionCode", "tradeDate", "tradeDirection",
            "purchaseBasePrice", "saleBasePrice", "carriageBoardAmount", "packagingAmount",
            "freightAmount", "packagingForm", "reporterName", "reporterPhone", "sampleName",
            "sampleContact", "latitude", "longitude", "purchaseVolume", "moisture", "evidencePhotoId");
    public static final List<String> XLSX_HEADERS = List.of("regionCode", "tradeDate", "tradeDirection",
            "purchaseBasePrice", "saleBasePrice", "carriageBoardAmount", "packagingAmount", "freightAmount",
            "packagingForm", "reporterPhone", "sampleName", "sampleContact", "latitude", "longitude",
            "purchaseVolume", "moisture", "evidencePhotoId");
    public static final List<String> XLSX_LABELS = List.of("所在地区代码", "交易日期", "买卖方向",
            "采购基础价（元/吨）", "销售基础价（元/吨）", "车板组成（元/吨）", "包装组成（元/吨）",
            "运费组成（元/吨）", "包装形态", "填报人联系方式", "填报对象/客户名称",
            "填报对象联系方式", "纬度（度）", "经度（度）", "采购量（吨）", "水分（%）",
            "现场水印照片编号");

    private MarketImportTemplate() {}

    public static String csv() { return String.join(",", HEADERS) + "\n"; }
    public static BusinessImportWorkbook.Template workbook(String productCode, String objectTypeCode) {
        return new BusinessImportWorkbook.Template(DOMAIN, "市场", productCode, objectTypeCode,
                XLSX_HEADERS, XLSX_LABELS);
    }

    public static List<List<String>> canonicalXlsx(byte[] bytes) {
        var sheet = BusinessImportWorkbook.read(bytes, DOMAIN, XLSX_HEADERS, XLSX_LABELS);
        java.util.ArrayList<List<String>> table = new java.util.ArrayList<>();
        table.add(HEADERS);
        for (List<String> row : sheet.rows()) {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("productCode", sheet.productCode());
            values.put("objectTypeCode", sheet.objectTypeCode());
            values.put("reporterName", "");
            for (int index = 0; index < XLSX_HEADERS.size(); index++) {
                values.put(XLSX_HEADERS.get(index), row.get(index));
            }
            table.add(HEADERS.stream().map(header -> values.getOrDefault(header, "")).toList());
        }
        return List.copyOf(table);
    }
}
