package com.cofco.qiqihar.graintrade.production.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the only field catalogue used by production XLSX, APIs and browser entry points. */
public final class ProductionSurveyFieldContract {
    public static final String VERSION = "production-survey-fields-v1";
    private static final Set<String> FIXED_CODES = Set.of(
            "objectTypeCode", "regionCode", "PROD_CULTIVAR_NAME", "surveyDate",
            "PROD_SAMPLE_SUBJECT_CODE", "PROD_SAMPLE_NAME", "PROD_REPORTER_NAME",
            "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE",
            "PROD_SAMPLE_LONGITUDE", "cultivatedAreaMu", "yieldPerMuKilograms",
            "estimatedOutputKilograms", "yearOnYear", "evidencePhotoId");

    private ProductionSurveyFieldContract() {}

    public static List<ProductionSurveyField> fields(List<ProductionFactGroup> groups) {
        List<ProductionSurveyField> fields = new ArrayList<>();
        fields.add(fixed("objectTypeCode", "样本点类型", "CONTEXT", "基础信息", 10, 10,
                "TEXT", "SELECT", null, true, false, false, false, true, 0, 0,
                "由当前业务入口受控选择"));
        fields.add(fixed("regionCode", "所在地区", "CONTEXT", "基础信息", 10, 20,
                "TEXT", "REGION", null, true, false, false, true, true, 0, 0,
                "完整行政区划路径或有效地区代码"));
        fields.add(fixed("PROD_CULTIVAR_NAME", "具体品种", "CONTEXT", "基础信息", 10, 30,
                "TEXT", "TEXT", null, false, false, false, true, true, 0, 0, null));
        fields.add(fixed("surveyDate", "调查日期", "CONTEXT", "基础信息", 10, 40,
                "DATE", "DATE", null, true, false, false, true, true, 0, 0,
                "格式 YYYY-MM-DD"));

        fields.add(fixed("PROD_SAMPLE_SUBJECT_CODE", "稳定主体码", "SUBJECT", "调查对象与联系", 20, 10,
                "TEXT", "READONLY_SUBJECT", null, false, true, false, false, true, 0, 0,
                "仅由权威映射回填；未映射时明确显示 EXT-007 待处理"));
        fields.add(fixed("PROD_SAMPLE_NAME", "填报对象名称", "SUBJECT", "调查对象与联系", 20, 20,
                "TEXT", "TEXT", null, false, false, false, true, true, 0, 0,
                "仅作展示名称，不作为稳定主体标识"));
        fields.add(fixed("PROD_REPORTER_NAME", "填报人", "SUBJECT", "调查对象与联系", 20, 30,
                "TEXT", "READONLY_TEXT", null, true, true, false, false, true, 0, 0,
                "由登录账号自动记录"));
        fields.add(fixed("PROD_REPORTER_PHONE", "填报人联系方式", "SUBJECT", "调查对象与联系", 20, 40,
                "TEXT", "TEXT", null, true, false, false, true, true, 0, 0, null));
        fields.add(fixed("PROD_SAMPLE_CONTACT", "填报对象联系方式", "SUBJECT", "调查对象与联系", 20, 50,
                "TEXT", "TEXT", null, true, false, false, true, true, 0, 0, null));
        fields.add(fixed("PROD_SAMPLE_LATITUDE", "填报对象纬度", "SUBJECT", "调查对象与联系", 20, 60,
                "DECIMAL", "DECIMAL", "度", true, false, false, true, true, 9, 6, "范围 -90 至 90"));
        fields.add(fixed("PROD_SAMPLE_LONGITUDE", "填报对象经度", "SUBJECT", "调查对象与联系", 20, 70,
                "DECIMAL", "DECIMAL", "度", true, false, false, true, true, 9, 6, "范围 -180 至 180"));

        fields.add(fixed("cultivatedAreaMu", "种植面积", "OUTPUT", "产量信息", 30, 10,
                "DECIMAL", "DECIMAL", "亩", true, false, false, true, true, 18, 4, null));
        fields.add(fixed("yieldPerMuKilograms", "权威采用单产", "OUTPUT", "产量信息", 30, 20,
                "DECIMAL", "DECIMAL", "公斤/亩", true, false, false, true, true, 18, 4, null));
        fields.add(fixed("estimatedOutputKilograms", "预计总产", "OUTPUT", "产量信息", 30, 30,
                "DECIMAL", "READONLY_DECIMAL", "公斤", false, true, true, false, true, 18, 4,
                "种植面积与权威采用单产的计算值"));
        fields.add(fixed("yearOnYear", "与上年同比", "OUTPUT", "产量信息", 30, 40,
                "TEXT", "READONLY_TEXT", null, false, true, true, false, true, 0, 0, null));

        Set<String> seen = new HashSet<>(fields.stream().map(ProductionSurveyField::code).toList());
        int groupOrder = 40;
        for (ProductionFactGroup group : groups) {
            int currentGroupOrder = groupOrder;
            group.fields().stream()
                    .filter(field -> !FIXED_CODES.contains(field.code()))
                    .filter(field -> seen.add(field.code()))
                    .forEach(field -> fields.add(new ProductionSurveyField(
                            field.code(), field.label(), group.category(), group.label(), currentGroupOrder,
                            field.sortOrder(), field.valueType(), controlType(field.valueType()), field.unit(),
                            false, List.of(), false, false, true, true, field.description(),
                            field.precision(), field.scale())));
            groupOrder += 10;
        }
        fields.add(fixed("evidencePhotoId", "现场水印照片编号", "EVIDENCE", "佐证材料", 90, 10,
                "UUID", "EVIDENCE", null, true, false, false, true, false, 0, 0, null));
        return List.copyOf(fields);
    }

    private static String controlType(String valueType) {
        return "DECIMAL".equals(valueType) ? "DECIMAL" : "TEXT";
    }

    private static ProductionSurveyField fixed(String code, String label, String groupCode, String groupLabel,
            int groupOrder, int sortOrder, String valueType, String controlType, String unit, boolean required,
            boolean readOnly, boolean calculated, boolean importable, boolean displayed, int precision, int scale,
            String description) {
        return new ProductionSurveyField(code, label, groupCode, groupLabel, groupOrder, sortOrder,
                valueType, controlType, unit, required, List.of(), readOnly, calculated, importable, displayed,
                description, precision, scale);
    }
}
