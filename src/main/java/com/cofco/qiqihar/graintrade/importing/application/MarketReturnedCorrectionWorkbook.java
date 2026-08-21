package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Generates and reads the dedicated workbook that updates returned market records in place. */
public final class MarketReturnedCorrectionWorkbook {
    public static final String PURPOSE = "MARKET_RETURNED_CORRECTION";
    public static final String ORIGINAL_RECORD_ID_CODE = "originalRecordId";
    public static final String ORIGINAL_VERSION_CODE = "originalVersion";
    public static final String ORIGINAL_BINDING_CODE = "originalBinding";
    private static final String ORIGINAL_RECORD_ID_LABEL = "原单编号（请勿修改）";
    private static final String ORIGINAL_VERSION_LABEL = "原单版本（系统校验）";
    private static final String ORIGINAL_BINDING_LABEL = "原单绑定（系统校验）";

    public record Row(String originalRecordId, long originalVersion, List<String> values) {
        public Row {
            if (originalRecordId == null || originalRecordId.isBlank() || originalVersion < 0) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_ROW");
            }
            originalRecordId = originalRecordId.trim();
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record ParsedRow(
            int worksheetRow, String originalRecordId, long originalVersion, List<String> values) {
        public ParsedRow {
            if (worksheetRow < 2 || originalRecordId == null || originalRecordId.isBlank()
                    || originalVersion < 0 || values == null) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_ROW");
            }
            originalRecordId = originalRecordId.trim();
            values = List.copyOf(values);
        }
    }

    private MarketReturnedCorrectionWorkbook() {}

    public static BusinessImportWorkbook.Template template(
            BusinessImportWorkbook.Template marketProductTemplate) {
        validateMarketProductTemplate(marketProductTemplate);
        List<Integer> editableIndexes = editableIndexes(marketProductTemplate);

        ArrayList<String> headers = new ArrayList<>();
        headers.add(ORIGINAL_RECORD_ID_CODE);
        headers.add(ORIGINAL_VERSION_CODE);
        headers.add(ORIGINAL_BINDING_CODE);
        editableIndexes.forEach(index -> headers.add(marketProductTemplate.headers().get(index)));

        ArrayList<String> labels = new ArrayList<>();
        labels.add(ORIGINAL_RECORD_ID_LABEL);
        labels.add(ORIGINAL_VERSION_LABEL);
        labels.add(ORIGINAL_BINDING_LABEL);
        editableIndexes.forEach(index -> labels.add(marketProductTemplate.labels().get(index)));

        ArrayList<BusinessImportWorkbook.ColumnRule> rules = new ArrayList<>();
        rules.add(new BusinessImportWorkbook.ColumnRule(
                ORIGINAL_RECORD_ID_CODE, "TEXT", "READONLY_TEXT", true,
                List.of(), 0, 0, "系统预填，请勿修改"));
        rules.add(new BusinessImportWorkbook.ColumnRule(
                ORIGINAL_VERSION_CODE, "DECIMAL", "READONLY_INTEGER", true,
                List.of(), 19, 0, "系统校验，请勿修改"));
        rules.add(new BusinessImportWorkbook.ColumnRule(
                ORIGINAL_BINDING_CODE, "TEXT", "READONLY_TEXT", true,
                List.of(), 0, 0, "系统校验，请勿修改"));
        editableIndexes.forEach(index -> rules.add(marketProductTemplate.rules().get(index)));

        return new BusinessImportWorkbook.Template(
                marketProductTemplate.domainCode(), marketProductTemplate.domainLabel(),
                marketProductTemplate.productCode(), marketProductTemplate.objectTypeCode(),
                marketProductTemplate.contractVersion(), null, headers, labels, rules);
    }

    public static byte[] create(BusinessImportWorkbook.Template marketProductTemplate,
            List<Row> rows, MarketReturnedCorrectionBinding binding) {
        BusinessImportWorkbook.Template correctionTemplate = template(marketProductTemplate);
        List<Row> safeRows = rows == null ? List.of() : List.copyOf(rows);
        int businessColumnCount = correctionTemplate.headers().size() - 3;
        ArrayList<List<String>> workbookRows = new ArrayList<>();
        for (Row row : safeRows) {
            if (row == null || row.values().size() != businessColumnCount) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_ROW");
            }
            ArrayList<String> values = new ArrayList<>();
            values.add(row.originalRecordId());
            values.add(Long.toString(row.originalVersion()));
            values.add(binding.sign(marketProductTemplate.productCode(),
                    row.originalRecordId(), row.originalVersion()));
            values.addAll(row.values());
            workbookRows.add(List.copyOf(values));
        }
        return BusinessImportWorkbook.create(correctionTemplate, workbookRows,
                new BusinessImportWorkbook.WorkbookOptions(
                        "退回记录修正", PURPOSE, "退回记录批量修正",
                        Set.of(ORIGINAL_VERSION_CODE, ORIGINAL_BINDING_CODE),
                        List.of(
                                List.of("修正范围", "仅修改原单内容，不新建、不作废原记录"),
                                List.of("处理结果", "修正后重新提交审核"))));
    }

    public static List<ParsedRow> read(byte[] workbook,
            BusinessImportWorkbook.Template marketProductTemplate,
            MarketReturnedCorrectionBinding binding) {
        if (!PURPOSE.equals(BusinessImportWorkbook.purpose(workbook))) {
            throw new IllegalArgumentException("INVALID_XLSX_PURPOSE");
        }
        BusinessImportWorkbook.Template correctionTemplate = template(marketProductTemplate);
        List<List<String>> rows = BusinessImportWorkbook.read(workbook, correctionTemplate).rows();
        ArrayList<ParsedRow> parsed = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            long originalVersion;
            try {
                originalVersion = Long.parseLong(row.get(1).trim());
                if (originalVersion < 0) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "INVALID_RETURNED_CORRECTION_VERSION: row " + (index + 2), exception);
            }
            String originalRecordId = row.getFirst().trim();
            if (!binding.matches(row.get(2), marketProductTemplate.productCode(),
                    originalRecordId, originalVersion)) {
                throw new IllegalArgumentException(
                        "INVALID_RETURNED_CORRECTION_BINDING: row " + (index + 2));
            }
            parsed.add(new ParsedRow(index + 2, originalRecordId, originalVersion,
                    row.subList(3, row.size())));
        }
        return List.copyOf(parsed);
    }

    public static List<String> businessHeaders(
            BusinessImportWorkbook.Template marketProductTemplate) {
        validateMarketProductTemplate(marketProductTemplate);
        return editableIndexes(marketProductTemplate).stream()
                .map(marketProductTemplate.headers()::get).toList();
    }

    private static void validateMarketProductTemplate(
            BusinessImportWorkbook.Template marketProductTemplate) {
        if (marketProductTemplate == null
                || !MarketImportTemplate.DOMAIN.equals(marketProductTemplate.domainCode())
                || marketProductTemplate.productCode() == null
                || marketProductTemplate.objectTypeCode() != null
                || marketProductTemplate.rules().size() != marketProductTemplate.headers().size()
                || !marketProductTemplate.headers().contains(BusinessImportWorkbook.PHOTO_FILENAMES_CODE)) {
            throw new IllegalArgumentException("INVALID_MARKET_RETURNED_CORRECTION_TEMPLATE");
        }
    }

    private static List<Integer> editableIndexes(
            BusinessImportWorkbook.Template marketProductTemplate) {
        return java.util.stream.IntStream.range(0, marketProductTemplate.headers().size())
                .filter(index -> !BusinessImportWorkbook.PHOTO_FILENAMES_CODE.equals(
                        marketProductTemplate.headers().get(index)))
                .boxed().toList();
    }
}
