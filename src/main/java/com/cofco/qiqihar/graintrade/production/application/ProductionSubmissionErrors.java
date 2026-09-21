package com.cofco.qiqihar.graintrade.production.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.Map;

/** Translates the domain's controlled validation reasons at the application boundary. */
final class ProductionSubmissionErrors {
    private ProductionSubmissionErrors() {}
    private static final Map<String, String> FIELDS = Map.ofEntries(
        Map.entry("cultivated area", "cultivatedAreaMu"), Map.entry("yield per mu", "yieldPerMuKilograms"),
        Map.entry("contact value", "PROD_SAMPLE_CONTACT"), Map.entry("sample contact", "PROD_SAMPLE_CONTACT"),
        Map.entry("surveyor phone", "PROD_SURVEYOR_PHONE"), Map.entry("latitude", "PROD_SAMPLE_LATITUDE"),
        Map.entry("longitude", "PROD_SAMPLE_LONGITUDE"), Map.entry("survey year", "surveyYear"),
        Map.entry("survey month", "surveyMonth"), Map.entry("survey date", "surveyDate"));
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("cultivatedAreaMu", "播种面积"), Map.entry("yieldPerMuKilograms", "预计单产"),
        Map.entry("PROD_SAMPLE_CONTACT", "样本点联系方式"), Map.entry("PROD_SURVEYOR_PHONE", "调研人联系方式"),
        Map.entry("PROD_SAMPLE_LATITUDE", "纬度"), Map.entry("PROD_SAMPLE_LONGITUDE", "经度"),
        Map.entry("surveyYear", "数据年份"), Map.entry("surveyMonth", "数据月份"), Map.entry("surveyDate", "调查日期"));
    static ClientRequestException invalid(String message) {
        if (message == null) return new ClientRequestException("INVALID_PRODUCTION_RECORD", "填写内容无效");
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        for (var entry : FIELDS.entrySet()) {
            if (!lower.startsWith(entry.getKey())) continue;
            String field = entry.getValue();
            String label = LABELS.get(field);
            String reason;
            if (lower.contains("negative")) reason = label + "不能为负数。";
            else if (lower.contains("precision")) reason = label + "超出允许精度，请减少整数位数并核对单位。";
            else if (lower.contains("required") || lower.contains("null")) reason = "请填写" + label + "。";
            else if (field.endsWith("CONTACT") || field.endsWith("PHONE")) reason = label + "须为 6 至 32 位电话号码，可含数字、加号、括号、短横线和空格。";
            else if (lower.contains("future") || lower.contains("after reported")) reason = label + "不能晚于填报日期。";
            else if (field.equals("surveyYear")) reason = "数据年份须在 1900 至 2200 之间。";
            else if (field.equals("surveyMonth")) reason = "数据月份须在 1 至 12 之间。";
            else if (field.endsWith("LATITUDE")) reason = "纬度须为 -90 至 90 之间的数值。";
            else if (field.endsWith("LONGITUDE")) reason = "经度须为 -180 至 180 之间的数值。";
            else reason = label + "格式不正确，请核对填写值。";
            return ClientRequestException.field("INVALID_PRODUCTION_RECORD", field, reason);
        }
        if (message.matches("PROD_[A-Z_]+ is (outside range|invalid|too long)")) {
            String field = message.substring(0, message.indexOf(' '));
            return ClientRequestException.field("INVALID_PRODUCTION_RECORD", field,
                    lower.endsWith("too long") ? "内容不能超过 500 个字符。" : "须填写非负数，最多 4 位小数且总位数不超过 18 位。");
        }
        return new ClientRequestException("INVALID_PRODUCTION_RECORD", message);
    }
}
