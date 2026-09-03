package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.application.GovernedDraftImportService.DraftWorkbookRow;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DraftWorkbookRows {
    private DraftWorkbookRows() {}

    static List<DraftWorkbookRow> map(BusinessImportWorkbook.ImportSheet sheet,
            BusinessImportWorkbook.Template template, List<String> codes,
            String objectTypeField, Map<String, String> objectTypeByLabel, String fixedObjectType,
            Map<String, Map<String, String>> valueCodesByLabel,
            String sampleCode, String regionCode, Set<String> systemGeneratedCodes) {
        return map(sheet, template, codes, objectTypeField, objectTypeByLabel, fixedObjectType,
                valueCodesByLabel, sampleCode, regionCode, systemGeneratedCodes, null, 0);
    }

    static List<DraftWorkbookRow> map(BusinessImportWorkbook.ImportSheet sheet,
            BusinessImportWorkbook.Template template, List<String> codes,
            String objectTypeField, Map<String, String> objectTypeByLabel, String fixedObjectType,
            Map<String, Map<String, String>> valueCodesByLabel,
            String sampleCode, String regionCode, Set<String> systemGeneratedCodes,
            String worksheetName, int rowNumberOffset) {
        if (template.headers().size() != codes.size() || template.labels().size() != codes.size()) {
            throw new IllegalArgumentException("INVALID_DRAFT_WORKBOOK_MAPPING");
        }
        List<DraftWorkbookRow> result = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < sheet.rows().size(); rowIndex++) {
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> normalizedValues = new LinkedHashMap<>();
            for (int column = 0; column < codes.size(); column++) {
                values.put(codes.get(column), sheet.submittedRows().get(rowIndex).get(column).trim());
                normalizedValues.put(codes.get(column), sheet.rows().get(rowIndex).get(column).trim());
            }
            systemGeneratedCodes.forEach(values::remove);
            systemGeneratedCodes.forEach(normalizedValues::remove);
            if (worksheetName != null) {
                values.put("工作表", worksheetName);
                values.put("工作表行号", Integer.toString(rowIndex + 2));
            }
            String objectType = fixedObjectType;
            String errorCode = null;
            String errorMessage = null;
            if (objectTypeField != null && !objectTypeField.isBlank()) {
                String supplied = values.getOrDefault(objectTypeField, "").trim();
                if (supplied.isBlank()) {
                    objectType = fixedObjectType;
                } else {
                    objectType = objectTypeByLabel.get(supplied);
                    if (objectType == null && objectTypeByLabel.containsValue(supplied)) objectType = supplied;
                    if (objectType == null) {
                        errorCode = "IMPORT_OBJECT_TYPE_INVALID";
                        errorMessage = "对象类型不在当前产品的填报范围内";
                    } else {
                        normalizedValues.put(objectTypeField, objectType);
                    }
                }
            }
            for (int column = 0; column < codes.size() && errorCode == null; column++) {
                String code = codes.get(column);
                if (systemGeneratedCodes.contains(code)) continue;
                String supplied = values.getOrDefault(code, "").trim();
                if (supplied.isBlank()) {
                    if (template.rules().get(column).required()) {
                        errorCode = "IMPORT_ROW_REQUIRED_VALUE";
                        errorMessage = "“" + template.labels().get(column) + "”为必填项";
                        if (worksheetName != null) values.put("错误列", template.labels().get(column));
                    }
                    continue;
                }
                Map<String, String> translations = valueCodesByLabel.get(code);
                if (translations != null) {
                    String normalizedSupplied = java.text.Normalizer.normalize(
                            supplied, java.text.Normalizer.Form.NFKC).trim();
                    String normalized = translations.get(normalizedSupplied);
                    if (normalized == null) {
                        errorCode = "IMPORT_VALUE_FORMAT_INVALID";
                        errorMessage = "“" + template.labels().get(column) + "”不在受控选项内；可填写："
                                + String.join("、", translations.keySet());
                        if (worksheetName != null) values.put("错误列", template.labels().get(column));
                    } else {
                        normalizedValues.put(code, normalized);
                    }
                    continue;
                }
                try {
                    BusinessImportWorkbook.validateCell(supplied, template.rules().get(column));
                } catch (IllegalArgumentException invalidValue) {
                    errorCode = "IMPORT_VALUE_FORMAT_INVALID";
                    errorMessage = "“" + template.labels().get(column) + "”填写不正确："
                            + BusinessImportWorkbook.validationHint(template.rules().get(column));
                    if (worksheetName != null) values.put("错误列", template.labels().get(column));
                    continue;
                }
            }
            List<String> missing = new ArrayList<>();
            int total = 0;
            int filled = 0;
            for (int column = 0; column < codes.size(); column++) {
                String code = codes.get(column);
                if (BusinessImportWorkbook.PHOTO_FILENAMES_CODE.equals(code)
                        || systemGeneratedCodes.contains(code)) continue;
                total++;
                String value = values.getOrDefault(code, "");
                if (value.isBlank()) missing.add(template.labels().get(column));
                else filled++;
            }
            String year = normalizedValues.getOrDefault("surveyYear", "").trim();
            String month = normalizedValues.getOrDefault("surveyMonth", "").trim();
            String period = year.isBlank() ? null : month.isBlank() ? year : year + "-" + padMonth(month);
            int completeness = total == 0 ? 100 : (int) Math.round(filled * 100.0d / total);
            result.add(new DraftWorkbookRow(rowNumberOffset + rowIndex + 2, objectType,
                    normalizedValues.getOrDefault(sampleCode, ""),
                    normalizedValues.getOrDefault(regionCode, ""), period,
                    values, normalizedValues, missing, completeness,
                    values.getOrDefault(BusinessImportWorkbook.PHOTO_FILENAMES_CODE, ""),
                    sampleCode, regionCode, BusinessImportWorkbook.PHOTO_FILENAMES_CODE,
                    objectTypeField == null ? "" : objectTypeField, errorCode, errorMessage));
        }
        return List.copyOf(result);
    }

    private static String padMonth(String month) {
        try {
            int value = Integer.parseInt(month);
            return value >= 1 && value <= 12 ? "%02d".formatted(value) : month;
        } catch (NumberFormatException exception) {
            return month;
        }
    }
}
