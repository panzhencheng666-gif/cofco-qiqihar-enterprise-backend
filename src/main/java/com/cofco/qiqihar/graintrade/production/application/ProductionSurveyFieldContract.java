package com.cofco.qiqihar.graintrade.production.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the only field catalogue used by production XLSX, APIs and browser entry points. */
public final class ProductionSurveyFieldContract {
    public static final String VERSION = "production-survey-fields-v1";
    public static final String DIGEST =
            "sha256:44997993c550cd093d2012bb0eb0520b5f693da046cca2573d4fbe6b93f62e32";
    private static final Set<String> FIXED_CODES = Set.of(
            "objectTypeCode", "regionCode", "PROD_CULTIVAR_NAME", "surveyYear", "surveyMonth", "surveyDate",
            "PROD_SAMPLE_SUBJECT_CODE", "PROD_SURPLUS_SUBJECT_CODE", "PROD_SURPLUS_CUTOFF_DATE",
            "PROD_SAMPLE_NAME", "PROD_REPORTER_NAME",
            "PROD_REPORTER_PHONE", "PROD_SAMPLE_CONTACT", "PROD_SAMPLE_LATITUDE",
            "PROD_SAMPLE_LONGITUDE", "cultivatedAreaMu", "yieldPerMuKilograms",
            "estimatedOutputKilograms", "yearOnYear", "evidencePhotoId");

    private ProductionSurveyFieldContract() {}

    public static List<ProductionSurveyField> fields(List<ProductionFactGroup> groups) {
        List<ProductionSurveyField> fields = new ArrayList<>();
        fields.add(fixed("objectTypeCode", "样本点类型", "CONTEXT", "基础信息", 10, 10,
                "TEXT", "SELECT", null, true, false, false, false, true, 0, 0,
                "由当前业务入口受控选择"));
        fields.add(fixed("regionCode", "地区", "CONTEXT", "基础信息", 10, 20,
                "TEXT", "REGION", null, true, false, false, true, true, 0, 0,
                "完整行政区划路径或有效地区代码"));
        fields.add(fixed("PROD_CULTIVAR_NAME", "具体品种", "CONTEXT", "基础信息", 10, 30,
                "TEXT", "TEXT", null, false, false, false, true, true, 0, 0, null));
        fields.add(fixed("surveyYear", "数据年份", "CONTEXT", "数据时间", 10, 40,
                "TEXT", "SELECT", null, true, false, false, true, true, 4, 0,
                "数据所属年份，1900—2200"));
        fields.add(fixed("surveyMonth", "数据月份", "CONTEXT", "数据时间", 10, 50,
                "TEXT", "SELECT", null, false, false, false, true, true, 2, 0,
                "可空；填写时为 1—12 月"));
        fields.add(fixed("surveyDate", "兼容调查日期", "CONTEXT", "兼容字段", 10, 60,
                "DATE", "READONLY_DATE", null, false, true, false, false, false, 0, 0,
                "由数据年份和月份生成的兼容日期"));

        fields.add(fixed("PROD_SAMPLE_NAME", "样本点名称", "SUBJECT", "样本点与联系", 20, 20,
                "TEXT", "TEXT", null, false, false, false, true, true, 0, 0,
                "仅作展示名称，不作为稳定主体标识"));
        fields.add(fixed("PROD_REPORTER_NAME", "填报人", "SUBJECT", "填报与定位", 20, 30,
                "TEXT", "READONLY_TEXT", null, true, true, false, false, true, 0, 0,
                "由登录账号自动记录"));
        fields.add(fixed("PROD_REPORTER_PHONE", "填报人联系方式", "SUBJECT", "填报与定位", 20, 40,
                "TEXT", "TEXT", null, true, false, false, true, true, 0, 0, null));
        fields.add(fixed("PROD_SAMPLE_CONTACT", "样本点联系方式", "SUBJECT", "填报与定位", 20, 50,
                "TEXT", "TEXT", null, true, false, false, true, true, 0, 0, null));
        fields.add(fixed("PROD_SAMPLE_LATITUDE", "纬度", "SUBJECT", "填报与定位", 20, 60,
                "DECIMAL", "DECIMAL", "度", true, false, false, true, true, 9, 6, "范围 -90 至 90"));
        fields.add(fixed("PROD_SAMPLE_LONGITUDE", "经度", "SUBJECT", "填报与定位", 20, 70,
                "DECIMAL", "DECIMAL", "度", true, false, false, true, true, 9, 6, "范围 -180 至 180"));

        fields.add(fixed("cultivatedAreaMu", "播种面积", "OUTPUT", "产量信息", 30, 10,
                "DECIMAL", "DECIMAL", "亩", true, false, false, true, true, 18, 4, null));
        fields.add(fixed("yieldPerMuKilograms", "预计单产", "OUTPUT", "产量信息", 30, 20,
                "DECIMAL", "DECIMAL", "公斤/亩", true, false, false, true, true, 18, 4, null));
        fields.add(fixed("estimatedOutputKilograms", "预计总产", "OUTPUT", "产量信息", 30, 30,
                "DECIMAL", "READONLY_DECIMAL", "公斤", false, true, true, false, true, 18, 4,
                "播种面积与预计单产的计算值"));
        fields.add(fixed("yearOnYear", "与上年相比", "OUTPUT", "产量信息", 30, 40,
                "TEXT", "READONLY_TEXT", null, false, true, true, false, true, 0, 0, null));

        Set<String> seen = new HashSet<>(fields.stream().map(ProductionSurveyField::code).toList());
        int groupOrder = 40;
        for (ProductionFactGroup group : groups) {
            int currentGroupOrder = groupOrder;
            group.fields().stream()
                    .filter(field -> !FIXED_CODES.contains(field.code()))
                    .filter(field -> seen.add(field.code()))
                    .forEach(field -> fields.add(businessField(field, group, currentGroupOrder)));
            groupOrder += 10;
        }
        fields.add(fixed("evidencePhotoId", "现场水印照片编号", "EVIDENCE", "佐证材料", 90, 10,
                "UUID", "EVIDENCE", null, false, false, false, false, false, 0, 0, null));
        return List.copyOf(fields);
    }

    private static String controlType(String valueType) {
        return "DECIMAL".equals(valueType) ? "DECIMAL" : "TEXT";
    }

    private static ProductionSurveyField businessField(
            ProductionFactDefinition field, ProductionFactGroup group, int groupOrder) {
        return new ProductionSurveyField(
                field.code(), field.label(), group.category(), group.label(), groupOrder,
                field.sortOrder(), field.valueType(), controlType(field.valueType()), field.unit(),
                false, List.of(), false, false, true, true,
                field.description(), field.precision(), field.scale());
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
