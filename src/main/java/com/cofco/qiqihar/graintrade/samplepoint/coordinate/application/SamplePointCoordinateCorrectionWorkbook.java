package com.cofco.qiqihar.graintrade.samplepoint.coordinate.application;

import com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Four-sheet, version-bound workbook used only for governed sample-point corrections. */
public final class SamplePointCoordinateCorrectionWorkbook {
    public static final String PURPOSE = "SAMPLE-POINT-COORDINATE-CORRECTION-V1";
    public static final String KEEP = "保留原坐标";
    public static final String CHANGE = "修正坐标";
    private static final List<String> LABELS = List.of(
            "样本点编号（只读）", "原版本（只读）", "样本点名称（只读）", "地区名称（只读）",
            "地区代码（只读）", "样本点类型（只读）", "原经度（只读）", "原纬度（只读）",
            "重复坐标组（只读）", "行绑定（只读）", "处理方式", "修正后经度",
            "修正后纬度", "坐标来源", "修正说明");

    private SamplePointCoordinateCorrectionWorkbook() {}

    public static byte[] create(UUID batchId, List<Row> rows) {
        if (batchId == null || rows == null || rows.isEmpty() || rows.size() > 5_000
                || rows.stream().map(Row::samplePointId).distinct().count() != rows.size()) {
            throw new IllegalArgumentException("INVALID_SAMPLE_POINT_CORRECTION_WORKBOOK");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", packageRelationships());
                entry(zip, "xl/workbook.xml", workbook());
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
                entry(zip, "xl/styles.xml", styles());
                entry(zip, "xl/worksheets/sheet1.xml", instructions(batchId, rows.size()));
                entry(zip, "xl/worksheets/sheet2.xml", correctionRows(rows));
                entry(zip, "xl/worksheets/sheet3.xml", duplicateGroups(rows));
                entry(zip, "xl/worksheets/sheet4.xml", binding(batchId));
            }
            return output.toByteArray();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("SAMPLE_POINT_CORRECTION_XLSX_FAILED", exception);
        }
    }

    public static ParsedWorkbook read(byte[] bytes) {
        List<List<String>> binding = XlsxTable.parseWorksheet(bytes, 4, 2, 3);
        if (binding.size() != 2
                || !binding.getFirst().equals(List.of("工作簿用途", "导出批次"))
                || !PURPOSE.equals(binding.get(1).getFirst())) {
            throw invalid();
        }
        UUID batchId;
        try {
            batchId = UUID.fromString(binding.get(1).get(1).trim());
        } catch (RuntimeException exception) {
            throw invalid();
        }
        List<List<String>> sheet = XlsxTable.parseWorksheet(bytes, 2, LABELS.size(), 5_002);
        if (sheet.isEmpty() || !sheet.getFirst().equals(LABELS)) throw invalid();
        ArrayList<Row> rows = new ArrayList<>();
        for (int index = 1; index < sheet.size(); index++) {
            List<String> values = sheet.get(index);
            if (values.stream().allMatch(String::isBlank)) continue;
            try {
                rows.add(new Row(
                        UUID.fromString(values.get(0).trim()), Long.parseLong(values.get(1).trim()),
                        values.get(2), values.get(4), values.get(3), values.get(5),
                        decimal(values.get(6), true), decimal(values.get(7), true),
                        values.get(8), values.get(9), values.get(10),
                        decimal(values.get(11), false), decimal(values.get(12), false),
                        values.get(13), values.get(14)));
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }
        if (rows.isEmpty() || rows.stream().map(Row::samplePointId).distinct().count() != rows.size()) {
            throw invalid();
        }
        return new ParsedWorkbook(batchId, rows);
    }

    private static BigDecimal decimal(String value, boolean required) {
        String normalized = value == null ? "" : value.trim().replace(",", "");
        if (normalized.isEmpty()) {
            if (required) throw invalid();
            return null;
        }
        return new BigDecimal(normalized);
    }

    private static String instructions(UUID batchId, int count) {
        List<List<String>> rows = List.of(
                List.of("治理范围", "仅修正现存样本点主数据坐标，不新增样本点或业务记录"),
                List.of("导出批次", batchId.toString()),
                List.of("待核对样本点", count + " 个"),
                List.of("填写规则", "每个重复坐标组必须且只能保留一个原坐标，其余样本点填写真实新坐标"),
                List.of("处理方式", "选择“保留原坐标”或“修正坐标”；修正时经度、纬度、来源和说明必填"),
                List.of("小数位", "不限制必须填写几位；系统按经纬度数值判断，123.51 与 123.5100 相同"),
                List.of("审核生效", "上传只生成待审核修正请求；独立审核通过后才更新地图"),
                List.of("安全提示", "请勿修改灰色只读列、表头、工作表名称或隐藏绑定页"));
        return worksheet(rows, List.of(18, 78), false, false, 9, "landscape", 1);
    }

    private static String correctionRows(List<Row> rows) {
        ArrayList<List<String>> values = new ArrayList<>();
        values.add(LABELS);
        rows.forEach(row -> values.add(List.of(
                row.samplePointId().toString(), Long.toString(row.expectedVersion()),
                row.canonicalName(), row.regionName(), row.regionCode(), row.kindCode(),
                text(row.originalLongitude()), text(row.originalLatitude()), row.duplicateGroupId(),
                row.rowBinding(), row.action(), text(row.correctedLongitude()),
                text(row.correctedLatitude()), row.coordinateSource(), row.correctionNote())));
        return worksheet(values, List.of(38, 12, 22, 18, 14, 18, 14, 14, 32, 38, 16, 14, 14, 22, 30),
                true, true, 8, "landscape", 2);
    }

    private static String duplicateGroups(List<Row> rows) {
        Map<String, Group> groups = new LinkedHashMap<>();
        rows.forEach(row -> groups.compute(row.duplicateGroupId(), (key, current) -> current == null
                ? new Group(row.originalLongitude(), row.originalLatitude(), 1)
                : new Group(current.longitude(), current.latitude(), current.count() + 1)));
        ArrayList<List<String>> values = new ArrayList<>();
        values.add(List.of("重复坐标组", "原经度", "原纬度", "样本点数量"));
        groups.forEach((id, group) -> values.add(List.of(id, text(group.longitude()),
                text(group.latitude()), Integer.toString(group.count()))));
        return worksheet(values, List.of(68, 16, 16, 14), true, false, 9, "landscape", 1);
    }

    private static String binding(UUID batchId) {
        return worksheet(List.of(
                List.of("工作簿用途", "导出批次"),
                List.of(PURPOSE, batchId.toString())), List.of(42, 38), false, false,
                9, "landscape", 1);
    }

    private static String worksheet(
            List<List<String>> rows, List<Integer> widths, boolean freezeHeader,
            boolean correctionStyles, int paperSize, String orientation, int fitToWidth) {
        StringBuilder sheetRows = new StringBuilder();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> values = rows.get(rowIndex);
            StringBuilder cells = new StringBuilder();
            for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                int style = rowIndex == 0 ? 1
                        : correctionStyles ? (columnIndex <= 9
                                ? (columnIndex == 0 || columnIndex == 8 || columnIndex == 9 ? 4 : 2)
                                : 3) : 0;
                cells.append("<c r=\"").append(column(columnIndex + 1)).append(rowIndex + 1)
                        .append("\" t=\"inlineStr\" s=\"").append(style)
                        .append("\"><is><t xml:space=\"preserve\">")
                        .append(xml(values.get(columnIndex))).append("</t></is></c>");
            }
            int height = rowIndex == 0 ? 34 : correctionStyles ? 24 : 28;
            sheetRows.append("<row r=\"").append(rowIndex + 1).append("\" ht=\"")
                    .append(height).append("\" customHeight=\"1\">")
                    .append(cells).append("</row>");
        }
        StringBuilder columns = new StringBuilder("<cols>");
        for (int index = 0; index < widths.size(); index++) {
            columns.append("<col min=\"").append(index + 1).append("\" max=\"")
                    .append(index + 1).append("\" width=\"").append(widths.get(index))
                    .append("\" customWidth=\"1\"/>");
        }
        columns.append("</cols>");
        String protection = correctionStyles
                ? "<sheetProtection sheet=\"1\" objects=\"1\" scenarios=\"1\" "
                        + "formatCells=\"0\" formatColumns=\"0\" formatRows=\"0\"/>"
                : "";
        String validation = correctionStyles
                ? "<dataValidations count=\"1\"><dataValidation type=\"list\" allowBlank=\"0\" "
                        + "showErrorMessage=\"1\" errorTitle=\"请选择处理方式\" "
                        + "error=\"只能选择保留原坐标或修正坐标\" sqref=\"K2:K5001\">"
                        + "<formula1>&quot;" + KEEP + "," + CHANGE + "&quot;</formula1>"
                        + "</dataValidation></dataValidations>"
                : "";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>"
                + (freezeHeader ? "<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"><pane ySplit=\"1\" "
                        + "topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                        + "</sheetView></sheetViews>"
                        : "<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"/></sheetViews>")
                + "<sheetFormatPr defaultRowHeight=\"22\"/>" + columns + "<sheetData>"
                + sheetRows + "</sheetData>"
                + protection
                + (freezeHeader ? "<autoFilter ref=\"A1:" + column(widths.size()) + "1\"/>" : "")
                + validation
                + "<pageMargins left=\"0.25\" right=\"0.25\" top=\"0.5\" bottom=\"0.5\" "
                + "header=\"0.2\" footer=\"0.2\"/>"
                + "<pageSetup paperSize=\"" + paperSize + "\" orientation=\"" + orientation
                + "\" fitToWidth=\"" + fitToWidth + "\" fitToHeight=\"0\"/>"
                + "</worksheet>";
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
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

    private static String workbook() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="填写说明" sheetId="1" r:id="rId1"/>
                    <sheet name="待修正样本点" sheetId="2" r:id="rId2"/>
                    <sheet name="重复坐标组" sheetId="3" r:id="rId3"/>
                    <sheet name="导出绑定" sheetId="4" state="hidden" r:id="rId4"/>
                  </sheets>
                  <definedNames><definedName name="_工作簿用途" hidden="1">&quot;SAMPLE-POINT-COORDINATE-CORRECTION-V1&quot;</definedName></definedNames>
                </workbook>
                """;
    }

    private static String workbookRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
                  <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet4.xml"/>
                  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    private static String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="3"><font><sz val="11"/><name val="Arial Unicode MS"/><family val="2"/><charset val="134"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial Unicode MS"/><family val="2"/><charset val="134"/></font><font><sz val="9"/><name val="Arial Unicode MS"/><family val="2"/><charset val="134"/></font></fonts>
                  <fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1678B8"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE7E6E6"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill></fills>
                  <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9E1E8"/></left><right style="thin"><color rgb="FFD9E1E8"/></right><top style="thin"><color rgb="FFD9E1E8"/></top><bottom style="thin"><color rgb="FFD9E1E8"/></bottom><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="center"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1" applyProtection="1"><alignment wrapText="1" vertical="center"/><protection locked="1"/></xf><xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1" applyProtection="1"><alignment wrapText="1" vertical="center"/><protection locked="0"/></xf><xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1" applyProtection="1"><alignment wrapText="1" vertical="center"/><protection locked="1"/></xf></cellXfs>
                  <cellStyles count="1"><cellStyle name="常规" xfId="0" builtinId="0"/></cellStyles>
                  <dxfs count="0"/>
                  <tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
                </styleSheet>
                """;
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String column(int oneBased) {
        StringBuilder name = new StringBuilder();
        for (int value = oneBased; value > 0; value /= 26) {
            int remainder = (value - 1) % 26;
            name.append((char) ('A' + remainder));
            value--;
        }
        return name.reverse().toString();
    }

    private static String text(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_SAMPLE_POINT_CORRECTION_WORKBOOK");
    }

    private record Group(BigDecimal longitude, BigDecimal latitude, int count) {}

    public record Row(
            UUID samplePointId, long expectedVersion, String canonicalName, String regionCode,
            String regionName, String kindCode, BigDecimal originalLongitude,
            BigDecimal originalLatitude, String duplicateGroupId, String rowBinding,
            String action, BigDecimal correctedLongitude, BigDecimal correctedLatitude,
            String coordinateSource, String correctionNote) {
        public Row {
            if (samplePointId == null || expectedVersion < 0 || originalLongitude == null
                    || originalLatitude == null) throw invalid();
            canonicalName = cleanRequired(canonicalName);
            regionCode = cleanRequired(regionCode);
            regionName = cleanRequired(regionName);
            kindCode = cleanRequired(kindCode);
            duplicateGroupId = cleanRequired(duplicateGroupId);
            rowBinding = cleanRequired(rowBinding);
            action = clean(action);
            coordinateSource = clean(coordinateSource);
            correctionNote = clean(correctionNote);
        }

        private static String cleanRequired(String value) {
            String cleaned = clean(value);
            if (cleaned.isEmpty()) throw invalid();
            return cleaned;
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record ParsedWorkbook(UUID batchId, List<Row> rows) {
        public ParsedWorkbook { rows = List.copyOf(rows); }
    }
}
