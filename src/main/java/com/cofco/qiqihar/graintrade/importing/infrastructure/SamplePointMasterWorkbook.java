package com.cofco.qiqihar.graintrade.importing.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SamplePointMasterWorkbook {
    public enum Kind {
        DESIGN("DESIGN_SAMPLE_POINT", "设计样本点"),
        FORMAL("FORMAL_SAMPLE_POINT", "正式样本");

        private final String domainCode;
        private final String label;

        Kind(String domainCode, String label) {
            this.domainCode = domainCode;
            this.label = label;
        }
    }

    public record Column(String code, String label, boolean required) {
        public Column {
            if (code == null || code.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("INVALID_SAMPLE_POINT_IMPORT_TEMPLATE");
            }
            code = code.trim();
            label = label.trim();
        }
    }

    public record Template(Kind kind, String version, String digest, List<Column> columns) {
        public Template {
            if (kind == null || version == null || version.isBlank()
                    || digest == null || !digest.startsWith("sha256:")
                    || columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("INVALID_SAMPLE_POINT_IMPORT_TEMPLATE");
            }
            version = version.trim();
            digest = digest.trim();
            columns = List.copyOf(columns);
        }
    }

    public record Row(int rowNumber, Map<String, String> values) {
        public Row {
            values = Map.copyOf(values);
        }
    }

    private SamplePointMasterWorkbook() {}

    public static byte[] create(Template template) {
        return create(template, List.of());
    }

    public static byte[] create(Template template, List<Map<String, String>> rows) {
        BusinessImportWorkbook.Template workbook = workbook(template);
        List<List<String>> values = rows.stream()
                .map(row -> template.columns().stream()
                        .map(column -> row.getOrDefault(column.code(), ""))
                        .toList())
                .toList();
        return BusinessImportWorkbook.create(workbook, values,
                new BusinessImportWorkbook.WorkbookOptions(
                        template.kind().label + "批量新增", null, null, java.util.Set.of(),
                        List.of(List.of("处理规则", "一次自动校验；任一行错误则本次零条入库"))));
    }

    public static List<Row> parse(byte[] bytes, Template expected, int maximumRows) {
        if (maximumRows < 1 || maximumRows > 5_000) {
            throw new IllegalArgumentException("SAMPLE_POINT_IMPORT_LIMIT_EXCEEDED");
        }
        try {
            var sheet = BusinessImportWorkbook.readDraft(bytes, workbook(expected), maximumRows);
            java.util.ArrayList<Row> rows = new java.util.ArrayList<>();
            for (int rowIndex = 0; rowIndex < sheet.rows().size(); rowIndex++) {
                List<String> cells = sheet.rows().get(rowIndex);
                Map<String, String> values = new LinkedHashMap<>();
                for (int columnIndex = 0; columnIndex < expected.columns().size(); columnIndex++) {
                    values.put(expected.columns().get(columnIndex).code(), cells.get(columnIndex).trim());
                }
                rows.add(new Row(rowIndex + 2, values));
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("INVALID_SAMPLE_POINT_IMPORT_FORMAT");
            }
            return List.copyOf(rows);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("SAMPLE_POINT_IMPORT_")) {
                throw exception;
            }
            if (exception.getMessage() != null
                    && (exception.getMessage().contains("CONTRACT_MISMATCH")
                            || exception.getMessage().contains("CONTEXT_MISMATCH")
                            || exception.getMessage().contains("INVALID_XLSX_CONTEXT"))) {
                throw new IllegalArgumentException(
                        "SAMPLE_POINT_IMPORT_TEMPLATE_MISMATCH", exception);
            }
            throw new IllegalArgumentException("INVALID_SAMPLE_POINT_IMPORT_FORMAT", exception);
        }
    }

    private static BusinessImportWorkbook.Template workbook(Template template) {
        List<String> headers = template.columns().stream().map(Column::code).toList();
        List<String> labels = template.columns().stream().map(Column::label).toList();
        List<BusinessImportWorkbook.ColumnRule> rules = template.columns().stream()
                .map(column -> new BusinessImportWorkbook.ColumnRule(
                        column.code(), "TEXT", "TEXT", column.required(), List.of(), 0, 0,
                        column.required() ? "必填" : "可选"))
                .toList();
        return new BusinessImportWorkbook.Template(
                template.kind().domainCode, template.kind().label, null, null,
                template.version(), template.digest(), headers, labels, rules);
    }
}
