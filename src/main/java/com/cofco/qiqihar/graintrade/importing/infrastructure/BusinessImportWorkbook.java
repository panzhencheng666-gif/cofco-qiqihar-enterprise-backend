package com.cofco.qiqihar.graintrade.importing.infrastructure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates the common, versioned XLSX protocol used by every business import domain. */
public final class BusinessImportWorkbook {
    public static final String VERSION = "1";

    public record Template(String domainCode, String domainLabel, String productCode, String objectTypeCode,
                           List<String> headers, List<String> labels) {
        public Template {
            domainCode = required(domainCode);
            domainLabel = required(domainLabel);
            productCode = required(productCode);
            objectTypeCode = required(objectTypeCode);
            headers = List.copyOf(headers);
            labels = List.copyOf(labels);
            if (headers.isEmpty() || headers.size() != labels.size() || headers.stream().anyMatch(String::isBlank)
                    || labels.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("INVALID_TEMPLATE");
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

    public record Context(String productCode, String objectTypeCode) {}

    public static Context context(byte[] bytes, String domainCode) {
        Map<String, String> context = instructionContext(bytes);
        if (!VERSION.equals(context.get("模板版本")) || !domainCode.equals(context.get("业务类型"))
                || blank(context.get("产品品种")) || blank(context.get("对象类型"))) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        }
        return new Context(context.get("产品品种"), context.get("对象类型"));
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

    private static Map<String, String> instructionContext(byte[] bytes) {
        List<List<String>> instructions = XlsxTable.parseWorksheet(bytes, 2, 2);
        Map<String, String> context = new LinkedHashMap<>();
        instructions.forEach(row -> context.put(row.getFirst().trim(), row.get(1).trim()));
        return context;
    }

    private static String dataSheet(Template template, List<List<String>> dataRows) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < dataRows.size(); index++) {
            rows.append(row(index + 3, dataRows.get(index), 0, false));
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                  <cols>%s</cols>
                  <sheetData>%s%s</sheetData>
                  <autoFilter ref="A1:%s1"/>
                </worksheet>
                """.formatted(columns(template.headers().size()), row(1, template.labels(), 1, false),
                row(2, template.headers(), 0, true) + rows, columnName(template.headers().size()));
    }

    private static String instructionSheet(Template template) {
        List<List<String>> metadata = List.of(
                List.of("模板版本", VERSION),
                List.of("业务类型", template.domainCode()),
                List.of("产品品种", template.productCode()),
                List.of("对象类型", template.objectTypeCode()));
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
            xml.append(row(index + 5, instructions.get(index), index == 0 ? 1 : 0, false));
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

    private static String columns(int count) {
        return "<col min=\"1\" max=\"" + count + "\" width=\"20\" customWidth=\"1\"/>";
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
                  <fonts count="2"><font><sz val="11"/><name val="等线"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="等线"/></font></fonts>
                  <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1678B8"/><bgColor indexed="64"/></patternFill></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf></cellXfs>
                </styleSheet>
                """;
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
