package com.cofco.qiqihar.graintrade.importing.infrastructure;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates the common, versioned XLSX protocol used by every business import domain. */
public final class BusinessImportWorkbook {
    public static final String VERSION = "1";

    public record ColumnRule(String code, String valueType, String controlType, boolean required,
                             List<String> options, int precision, int scale, String description) {
        public ColumnRule {
            code = requiredText(code);
            valueType = requiredText(valueType);
            controlType = requiredText(controlType);
            options = options == null ? List.of() : List.copyOf(options);
        }

        private static String requiredText(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("INVALID_COLUMN_RULE");
            return value.trim();
        }
    }

    public record Template(String domainCode, String domainLabel, String productCode, String objectTypeCode,
                           String contractVersion, List<String> headers, List<String> labels,
                           List<ColumnRule> rules) {
        public Template {
            domainCode = required(domainCode);
            domainLabel = required(domainLabel);
            productCode = required(productCode);
            objectTypeCode = required(objectTypeCode);
            contractVersion = contractVersion == null || contractVersion.isBlank()
                    ? null : contractVersion.trim();
            headers = List.copyOf(headers);
            labels = List.copyOf(labels);
            rules = rules == null ? List.of() : List.copyOf(rules);
            if (headers.isEmpty() || headers.size() != labels.size() || headers.stream().anyMatch(String::isBlank)
                    || labels.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("INVALID_TEMPLATE");
            if (!rules.isEmpty() && (rules.size() != headers.size()
                    || !rules.stream().map(ColumnRule::code).toList().equals(headers))) {
                throw new IllegalArgumentException("INVALID_TEMPLATE_RULES");
            }
        }

        public Template(String domainCode, String domainLabel, String productCode, String objectTypeCode,
                List<String> headers, List<String> labels) {
            this(domainCode, domainLabel, productCode, objectTypeCode, null, headers, labels, List.of());
        }

        private static String required(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("INVALID_TEMPLATE_CONTEXT");
            return value.trim();
        }
    }

    private BusinessImportWorkbook() {}

    public static byte[] create(Template template) {
        return create(template, List.of());
    }

    public static byte[] create(Template template, List<List<String>> dataRows) {
        List<List<String>> safeRows = dataRows == null ? List.of() : dataRows.stream().map(List::copyOf).toList();
        if (safeRows.size() > 5_000 || safeRows.stream().anyMatch(row -> row.size() != template.headers().size())) {
            throw new IllegalArgumentException("INVALID_TEMPLATE_ROWS");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", packageRelationships());
                entry(zip, "xl/workbook.xml", workbook(template));
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
                entry(zip, "xl/styles.xml", styles());
                entry(zip, "xl/worksheets/sheet1.xml", dataSheet(template, safeRows));
                entry(zip, "xl/worksheets/sheet2.xml", instructionSheet(template));
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("XLSX_TEMPLATE_GENERATION_FAILED", exception);
        }
    }

    public record ImportSheet(String productCode, String objectTypeCode, List<List<String>> rows) {}

    public record Context(String productCode, String objectTypeCode, String contractVersion) {}

    public static Context context(byte[] bytes, String domainCode) {
        Map<String, String> context = instructionContext(bytes);
        if (!VERSION.equals(context.get("模板版本")) || !domainCode.equals(context.get("业务类型"))
                || blank(context.get("产品品种")) || blank(context.get("对象类型"))) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        }
        return new Context(context.get("产品品种"), context.get("对象类型"), context.get("字段契约版本"));
    }

    public static ImportSheet read(byte[] bytes, String domainCode, List<String> headers, List<String> labels) {
        return read(bytes, domainCode, headers, labels, 5_000);
    }

    public static ImportSheet read(byte[] bytes, String domainCode, List<String> headers, List<String> labels,
            int maxDataRows) {
        Context context = context(bytes, domainCode);
        List<List<String>> sheet = XlsxTable.parseWorksheet(bytes, 1, headers.size(), maxDataRows + 2);
        if (sheet.size() < 2 || !sheet.get(0).equals(labels) || !sheet.get(1).equals(headers)) {
            throw new IllegalArgumentException("INVALID_XLSX_TEMPLATE");
        }
        return new ImportSheet(context.productCode(), context.objectTypeCode(),
                List.copyOf(sheet.subList(2, sheet.size())));
    }

    public static ImportSheet read(byte[] bytes, Template template) {
        return read(bytes, template, 5_000);
    }

    public static ImportSheet read(byte[] bytes, Template template, int maxDataRows) {
        Context context = context(bytes, template.domainCode());
        if (!template.productCode().equals(context.productCode())
                || !template.objectTypeCode().equals(context.objectTypeCode())) {
            throw new IllegalArgumentException("XLSX_CONTEXT_MISMATCH");
        }
        if (template.contractVersion() != null
                && !template.contractVersion().equals(context.contractVersion())) {
            throw new IllegalArgumentException("XLSX_CONTRACT_MISMATCH");
        }
        List<List<String>> sheet = XlsxTable.parseWorksheet(
                bytes, 1, template.headers().size(), maxDataRows + 2);
        if (sheet.size() < 2 || !sheet.get(0).equals(template.labels())
                || !sheet.get(1).equals(template.headers())) {
            throw new IllegalArgumentException("INVALID_XLSX_TEMPLATE");
        }
        List<List<String>> rows = normalizeRows(
                List.copyOf(sheet.subList(2, sheet.size())), template.rules());
        validateRows(rows, template.rules());
        return new ImportSheet(context.productCode(), context.objectTypeCode(), rows);
    }

    private static List<List<String>> normalizeRows(List<List<String>> rows, List<ColumnRule> rules) {
        if (rules.isEmpty()) return rows;
        return rows.stream().map(row -> {
            ArrayList<String> values = new ArrayList<>(row);
            for (int index = 0; index < rules.size(); index++) {
                if ("DATE".equals(rules.get(index).valueType()) && !values.get(index).isBlank()) {
                    values.set(index, normalizeExcelDate(values.get(index).trim()));
                }
            }
            return List.copyOf(values);
        }).toList();
    }

    private static String normalizeExcelDate(String value) {
        try {
            return LocalDate.parse(value).toString();
        } catch (RuntimeException ignored) {
            try {
                long days = new BigDecimal(value).longValueExact();
                return LocalDate.of(1899, 12, 30).plusDays(days).toString();
            } catch (RuntimeException invalidExcelDate) {
                return value;
            }
        }
    }

    private static Map<String, String> instructionContext(byte[] bytes) {
        List<List<String>> instructions = XlsxTable.parseWorksheet(bytes, 2, 2);
        Map<String, String> context = new LinkedHashMap<>();
        instructions.forEach(row -> context.put(row.getFirst().trim(), row.get(1).trim()));
        return context;
    }

    private static String dataSheet(Template template, List<List<String>> dataRows) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < dataRows.size(); index++) {
            rows.append(dataRow(index + 3, dataRows.get(index), template.rules()));
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                  <cols>%s</cols>
                  <sheetData>%s%s</sheetData>
                  <autoFilter ref="A1:%s1"/>
                  %s
                </worksheet>
                """.formatted(columns(template), row(1, template.labels(), 1, false),
                row(2, template.headers(), 0, true) + rows, columnName(template.headers().size()),
                dataValidations(template));
    }

    private static String instructionSheet(Template template) {
        java.util.ArrayList<List<String>> metadata = new java.util.ArrayList<>(List.of(
                List.of("模板版本", VERSION),
                List.of("业务类型", template.domainCode()),
                List.of("产品品种", template.productCode()),
                List.of("对象类型", template.objectTypeCode())));
        if (template.contractVersion() != null) {
            metadata.add(List.of("字段契约版本", template.contractVersion()));
        }
        StringBuilder xml = new StringBuilder();
        for (int index = 0; index < metadata.size(); index++) {
            xml.append(row(index + 1, metadata.get(index), 0, true));
        }
        java.util.ArrayList<List<String>> instructions = new java.util.ArrayList<>(List.of(
                List.of("填报说明", "请按字段名称填写，不得修改表头或隐藏的模板校验信息"),
                List.of("填报人", "由登录账号自动记录，不得在模板中填写")));
        if ("PRODUCTION".equals(template.domainCode()) && template.headers().contains("regionCode")) {
            instructions.add(List.of("所在地区",
                    "请填写完整行政区划路径，如“齐齐哈尔市 / 梅里斯达斡尔族区 / 雅尔塞镇 / 音钦村”；旧模板可继续填写有效地区代码"));
        }
        instructions.add(List.of("处理方式", "5000 条以内即时处理；5001 至 50000 条转入后台任务处理"));
        for (int index = 0; index < instructions.size(); index++) {
            xml.append(row(index + metadata.size() + 1, instructions.get(index), index == 0 ? 1 : 0, false));
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <cols><col min="1" max="1" width="20" customWidth="1"/><col min="2" max="2" width="48" customWidth="1"/></cols>
                  <sheetData>%s</sheetData>
                </worksheet>
                """.formatted(xml);
    }

    private static String row(int number, List<String> values, int style, boolean hidden) {
        StringBuilder cells = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            cells.append("<c r=\"").append(columnName(index + 1)).append(number)
                    .append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t>")
                    .append(xml(values.get(index))).append("</t></is></c>");
        }
        return "<row r=\"" + number + "\"" + (hidden ? " hidden=\"1\"" : "") + ">" + cells + "</row>";
    }

    private static String dataRow(int number, List<String> values, List<ColumnRule> rules) {
        StringBuilder cells = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            int style = rules.isEmpty() ? 0 : style(rules.get(index));
            cells.append("<c r=\"").append(columnName(index + 1)).append(number)
                    .append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t>")
                    .append(xml(values.get(index))).append("</t></is></c>");
        }
        return "<row r=\"" + number + "\">" + cells + "</row>";
    }

    private static String columns(Template template) {
        if (template.rules().isEmpty()) {
            return "<col min=\"1\" max=\"" + template.headers().size()
                    + "\" width=\"20\" customWidth=\"1\"/>";
        }
        StringBuilder columns = new StringBuilder();
        for (int index = 0; index < template.rules().size(); index++) {
            columns.append("<col min=\"").append(index + 1).append("\" max=\"").append(index + 1)
                    .append("\" width=\"20\" customWidth=\"1\" style=\"")
                    .append(style(template.rules().get(index))).append("\"/>");
        }
        return columns.toString();
    }

    private static String columnName(int oneBased) {
        StringBuilder value = new StringBuilder();
        int column = oneBased;
        while (column > 0) {
            column--;
            value.append((char) ('A' + column % 26));
            column /= 26;
        }
        return value.reverse().toString();
    }

    private static String workbook(Template template) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="%s填报" sheetId="1" r:id="rId1"/><sheet name="填报说明" sheetId="2" r:id="rId2"/></sheets>
                </workbook>
                """.formatted(xml(template.domainLabel()));
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>
                """;
    }

    private static String packageRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """;
    }

    private static String workbookRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/><numFmt numFmtId="165" formatCode="0.##################"/></numFmts>
                  <fonts count="2"><font><sz val="11"/><name val="等线"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="等线"/></font></fonts>
                  <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1678B8"/><bgColor indexed="64"/></patternFill></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
                </styleSheet>
                """;
    }

    private static String dataValidations(Template template) {
        if (template.rules().isEmpty()) return "";
        StringBuilder validations = new StringBuilder();
        for (int index = 0; index < template.rules().size(); index++) {
            ColumnRule rule = template.rules().get(index);
            String column = columnName(index + 1);
            String promptTitle = rule.required() ? "必填字段" : "字段格式";
            String prompt = rule.required() ? "必填；" + formatHint(rule) : formatHint(rule);
            validations.append("<dataValidation showInputMessage=\"1\" showErrorMessage=\"1\" errorStyle=\"stop\"")
                    .append(" allowBlank=\"").append(rule.required() ? "0" : "1").append("\"")
                    .append(" promptTitle=\"").append(xml(promptTitle)).append("\"")
                    .append(" prompt=\"").append(xml(prompt)).append("\"")
                    .append(" errorTitle=\"字段校验失败\" error=\"").append(xml(prompt)).append("\"");
            validations.append(validationAttributes(rule)).append(" sqref=\"")
                    .append(column).append("3:").append(column).append("5002\">")
                    .append(validationFormula(rule, column)).append("</dataValidation>");
        }
        return "<dataValidations count=\"" + template.rules().size() + "\">"
                + validations + "</dataValidations>";
    }

    private static String validationAttributes(ColumnRule rule) {
        if (!rule.options().isEmpty()) {
            return " type=\"list\"";
        }
        if ("DECIMAL".equals(rule.valueType())) {
            return " type=\"decimal\" operator=\"between\"";
        }
        if ("DATE".equals(rule.valueType())) {
            return " type=\"date\" operator=\"between\"";
        }
        if ("UUID".equals(rule.valueType())) {
            return " type=\"textLength\" operator=\"equal\"";
        }
        if (rule.required()) {
            return " type=\"custom\"";
        }
        return "";
    }

    private static String validationFormula(ColumnRule rule, String column) {
        if (!rule.options().isEmpty()) {
            return "<formula1>&quot;" + xml(String.join(",", rule.options())) + "&quot;</formula1>";
        }
        if ("DECIMAL".equals(rule.valueType())) {
            return "<formula1>-1E+307</formula1><formula2>1E+307</formula2>";
        }
        if ("DATE".equals(rule.valueType())) {
            return "<formula1>DATE(2000,1,1)</formula1><formula2>DATE(2100,12,31)</formula2>";
        }
        if ("UUID".equals(rule.valueType())) {
            return "<formula1>36</formula1>";
        }
        if (rule.required()) {
            return "<formula1>LEN(TRIM(" + column + "3))&gt;0</formula1>";
        }
        return "";
    }

    private static String formatHint(ColumnRule rule) {
        String base = switch (rule.valueType()) {
            case "DECIMAL" -> "数字，精度 " + rule.precision() + "，小数位不超过 " + rule.scale();
            case "DATE" -> "日期格式 YYYY-MM-DD";
            case "UUID" -> "UUID 格式";
            default -> rule.options().isEmpty() ? "文本" : "请从受控选项中选择";
        };
        return rule.description() == null || rule.description().isBlank()
                ? base : base + "；" + rule.description();
    }

    private static int style(ColumnRule rule) {
        return switch (rule.valueType()) {
            case "DATE" -> 2;
            case "DECIMAL" -> 3;
            default -> 0;
        };
    }

    private static void validateRows(List<List<String>> rows, List<ColumnRule> rules) {
        if (rules.isEmpty()) return;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().allMatch(String::isBlank)) continue;
            for (int columnIndex = 0; columnIndex < rules.size(); columnIndex++) {
                ColumnRule rule = rules.get(columnIndex);
                String value = row.get(columnIndex).trim();
                if (value.isEmpty()) {
                    if (rule.required()) {
                        throw new IllegalArgumentException(
                                "XLSX_REQUIRED_VALUE: row " + (rowIndex + 3) + ", field " + rule.code());
                    }
                    continue;
                }
                validateValue(value, rule, rowIndex + 3);
            }
        }
    }

    private static void validateValue(String value, ColumnRule rule, int rowNumber) {
        try {
            if (!rule.options().isEmpty() && !rule.options().contains(value)) {
                throw new IllegalArgumentException("not in enum");
            }
            switch (rule.valueType()) {
                case "DECIMAL" -> {
                    BigDecimal decimal = new BigDecimal(value);
                    if (rule.precision() > 0 && decimal.precision() > rule.precision()) {
                        throw new IllegalArgumentException("precision");
                    }
                    if (rule.scale() >= 0 && decimal.scale() > rule.scale()) {
                        throw new IllegalArgumentException("scale");
                    }
                }
                case "DATE" -> LocalDate.parse(value);
                case "UUID" -> UUID.fromString(value);
                default -> { }
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "XLSX_VALUE_FORMAT: row " + rowNumber + ", field " + rule.code(), exception);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
