package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Domain-neutral workbook envelope for updating a returned original record in place. */
final class OperationalReturnedCorrectionWorkbook {
    static final String ORIGINAL_RECORD_ID = "originalRecordId";
    static final String ORIGINAL_VERSION = "originalVersion";
    static final String ORIGINAL_BINDING = "originalBinding";

    record Row(String originalRecordId, long originalVersion, List<String> values) {
        Row {
            if (originalRecordId == null || originalRecordId.isBlank()
                    || originalVersion < 0 || values == null) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_ROW");
            }
            originalRecordId = originalRecordId.trim();
            values = List.copyOf(values);
        }
    }

    record ParsedRow(
            int worksheetRow, String originalRecordId, long originalVersion,
            List<String> values) {
        ParsedRow {
            originalRecordId = originalRecordId.trim();
            values = List.copyOf(values);
        }
    }

    private OperationalReturnedCorrectionWorkbook() {}

    static BusinessImportWorkbook.Template template(BusinessImportWorkbook.Template ordinary) {
        validate(ordinary);
        List<Integer> indexes = businessIndexes(ordinary);
        ArrayList<String> headers = new ArrayList<>(List.of(
                ORIGINAL_RECORD_ID, ORIGINAL_VERSION, ORIGINAL_BINDING));
        indexes.forEach(index -> headers.add(ordinary.headers().get(index)));
        ArrayList<String> labels = new ArrayList<>(List.of(
                "原单编号（请勿修改）", "原单版本（系统校验）", "原单绑定（系统校验）"));
        indexes.forEach(index -> labels.add(ordinary.labels().get(index)));
        ArrayList<BusinessImportWorkbook.ColumnRule> rules = new ArrayList<>();
        rules.add(new BusinessImportWorkbook.ColumnRule(
                ORIGINAL_RECORD_ID, "TEXT", "READONLY_TEXT", true,
                List.of(), 0, 0, "系统预填，请勿修改"));
        rules.add(new BusinessImportWorkbook.ColumnRule(
                ORIGINAL_VERSION, "DECIMAL", "READONLY_INTEGER", true,
                List.of(), 19, 0, "系统校验，请勿修改"));
        rules.add(new BusinessImportWorkbook.ColumnRule(
                ORIGINAL_BINDING, "TEXT", "READONLY_TEXT", true,
                List.of(), 0, 0, "系统校验，请勿修改"));
        indexes.forEach(index -> rules.add(ordinary.rules().get(index)));
        return new BusinessImportWorkbook.Template(
                ordinary.domainCode(), ordinary.domainLabel(), ordinary.productCode(),
                ordinary.objectTypeCode(), ordinary.contractVersion(), null,
                headers, labels, rules);
    }

    static byte[] create(BusinessImportWorkbook.Template ordinary, List<Row> rows,
            OperationalReturnedCorrectionBinding binding) {
        BusinessImportWorkbook.Template correction = template(ordinary);
        String purpose = purpose(ordinary.domainCode());
        int businessColumns = correction.headers().size() - 3;
        List<List<String>> workbookRows = rows.stream().map(row -> {
            if (row.values().size() != businessColumns) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_ROW");
            }
            ArrayList<String> values = new ArrayList<>();
            values.add(row.originalRecordId());
            values.add(Long.toString(row.originalVersion()));
            values.add(binding.sign(purpose, ordinary.productCode(),
                    row.originalRecordId(), row.originalVersion()));
            values.addAll(row.values());
            return List.copyOf(values);
        }).toList();
        return BusinessImportWorkbook.create(correction, workbookRows,
                new BusinessImportWorkbook.WorkbookOptions(
                        "退回记录修正", purpose, "退回记录批量修正",
                        Set.of(ORIGINAL_VERSION, ORIGINAL_BINDING),
                        List.of(
                                List.of("修正范围", "只更新原单，不新建、不作废业务记录"),
                                List.of("处理结果", "修正后重新提交审核并刷新待办"))));
    }

    static List<ParsedRow> read(byte[] bytes, BusinessImportWorkbook.Template ordinary,
            OperationalReturnedCorrectionBinding binding) {
        String purpose = purpose(ordinary.domainCode());
        if (!purpose.equals(BusinessImportWorkbook.purpose(bytes))) {
            throw new IllegalArgumentException("INVALID_XLSX_PURPOSE");
        }
        List<List<String>> rows = BusinessImportWorkbook.read(bytes, template(ordinary)).rows();
        ArrayList<ParsedRow> parsed = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            String originalId = row.getFirst().trim();
            long version;
            try {
                version = Long.parseLong(row.get(1).trim());
                if (version < 0) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_VERSION", exception);
            }
            if (!binding.matches(row.get(2), purpose, ordinary.productCode(), originalId, version)) {
                throw new IllegalArgumentException("INVALID_RETURNED_CORRECTION_BINDING");
            }
            parsed.add(new ParsedRow(index + 2, originalId, version,
                    row.subList(3, row.size())));
        }
        return List.copyOf(parsed);
    }

    static String purpose(String domainCode) {
        return domainCode + "_RETURNED_CORRECTION";
    }

    private static List<Integer> businessIndexes(BusinessImportWorkbook.Template ordinary) {
        return java.util.stream.IntStream.range(0, ordinary.headers().size())
                .filter(index -> !BusinessImportWorkbook.PHOTO_FILENAMES_CODE.equals(
                        ordinary.headers().get(index)))
                .filter(index -> !BusinessImportWorkbook.PHOTO_FILENAMES_LABEL.equals(
                        ordinary.headers().get(index)))
                .boxed().toList();
    }

    private static void validate(BusinessImportWorkbook.Template ordinary) {
        if (ordinary == null || ordinary.domainCode() == null
                || ordinary.productCode() == null
                || ordinary.rules().size() != ordinary.headers().size()
                || !(ProductionImportTemplate.DOMAIN.equals(ordinary.domainCode())
                    || LogisticsImportTemplate.DOMAIN.equals(ordinary.domainCode()))) {
            throw new IllegalArgumentException("INVALID_OPERATIONAL_RETURNED_CORRECTION_TEMPLATE");
        }
    }
}
