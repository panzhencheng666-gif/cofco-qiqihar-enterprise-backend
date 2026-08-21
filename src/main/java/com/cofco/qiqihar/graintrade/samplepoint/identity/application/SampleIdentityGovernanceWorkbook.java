package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import com.cofco.qiqihar.graintrade.shared.spreadsheet.XlsxTable;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Version-bound workbook for reviewed, append-only historical sample identity governance. */
public final class SampleIdentityGovernanceWorkbook {
    public static final String PURPOSE = "SAMPLE-IDENTITY-GOVERNANCE-V1";
    public static final String MERGE = "归并至规范样本点";
    public static final String KEEP_DISTINCT = "保留为不同身份";
    public static final String DEFER = "暂不处理";
    private static final Set<String> ACTIONS = Set.of(MERGE, KEEP_DISTINCT, DEFER);
    private static final List<String> LABELS = List.of(
            "业务记录编号（只读）", "原版本（只读）", "业务域（只读）", "产品（只读）",
            "数据期间（只读）", "当前样本点编号（只读）", "样本点名称（只读）",
            "联系方式（只读）", "地区代码（只读）", "地区名称（只读）",
            "经度（只读）", "纬度（只读）", "已审核记录数（只读）",
            "重复身份组（只读）", "行绑定（只读）", "当前身份摘要（只读）",
            "处理方式", "规范样本点编号", "核验依据", "备注");

    private SampleIdentityGovernanceWorkbook() {}

    public static byte[] create(UUID batchId, List<Row> rows) {
        if (batchId == null || rows == null || rows.isEmpty() || rows.size() > 5_000
                || rows.stream().map(Row::sourceRecordId).distinct().count() != rows.size()) {
            throw invalid();
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
                entry(zip, "xl/worksheets/sheet2.xml", detailRows(rows));
                entry(zip, "xl/worksheets/sheet3.xml", identityGroups(rows));
                entry(zip, "xl/worksheets/sheet4.xml", binding(batchId));
            }
            return output.toByteArray();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("SAMPLE_IDENTITY_GOVERNANCE_XLSX_FAILED", exception);
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
        List<Row> rows = new ArrayList<>();
        for (int index = 1; index < sheet.size(); index++) {
            List<String> value = sheet.get(index);
            if (value.stream().allMatch(String::isBlank)) continue;
            try {
                rows.add(new Row(
                        value.get(0), Long.parseLong(value.get(1).trim()), value.get(2), value.get(3),
                        value.get(4), UUID.fromString(value.get(5).trim()), value.get(6), value.get(7),
                        value.get(8), value.get(9), decimal(value.get(10)), decimal(value.get(11)),
                        Integer.parseInt(value.get(12).trim()), value.get(13), value.get(14),
                        value.get(16), uuid(value.get(17)), value.get(18), value.get(19)));
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }
        if (rows.isEmpty() || rows.size() > 5_000
                || rows.stream().map(Row::sourceRecordId).distinct().count() != rows.size()) {
            throw invalid();
        }
        return new ParsedWorkbook(batchId, rows);
    }

    private static String instructions(UUID batchId, int count) {
        return worksheet(List.of(
                List.of("治理范围", "仅治理历史业务记录的样本身份关联，不删除样本点、不修改业务事实"),
                List.of("导出批次", batchId.toString()),
                List.of("待核验记录", count + " 条"),
                List.of("归并", "确认是同一真实样本点时，选择“归并至规范样本点”并填写规范样本点编号与依据"),
                List.of("保留", "确认只是重名或证据表明为不同对象时，选择“保留为不同身份”并填写依据"),
                List.of("暂缓", "证据不足时选择“暂不处理”，系统保持现状且继续列入待治理范围"),
                List.of("审核生效", "上传只生成归并请求；独立审核通过后追加身份解析，不改原记录编号和事实"),
                List.of("安全提示", "请勿修改灰色只读列、表头、工作表名称或隐藏绑定页")),
                List.of(18, 82), false, false, 9, "landscape", 1);
    }

    private static String detailRows(List<Row> rows) {
        List<List<String>> values = new ArrayList<>();
        values.add(LABELS);
        rows.forEach(row -> values.add(List.of(
                row.sourceRecordId(), Long.toString(row.sourceVersion()), row.sourceDomain(),
                row.productCode(), row.surveyPeriod(), row.currentSamplePointId().toString(),
                row.sampleName(), row.sampleContact(), row.regionCode(), row.regionName(),
                text(row.longitude()), text(row.latitude()), Integer.toString(row.approvedRecordCount()),
                row.duplicateIdentityGroup(), row.rowBinding(), identitySummary(row), row.action(),
                text(row.targetSamplePointId()), row.reviewBasis(), row.note())));
        return worksheet(values,
                List.of(38, 12, 14, 14, 14, 38, 22, 18, 14, 20, 14, 14, 14, 34, 38, 42, 20, 38, 34, 26),
                true, true, 8, "landscape", 3);
    }

    private static String identityGroups(List<Row> rows) {
        Map<String, Group> groups = new LinkedHashMap<>();
        rows.forEach(row -> groups.compute(row.duplicateIdentityGroup(), (key, group) -> {
            if (group == null) {
                return new Group(row.sampleName(), row.sampleContact(), row.regionName(),
                        row.longitude(), row.latitude(), 1,
                        new java.util.LinkedHashSet<>(Set.of(row.currentSamplePointId())));
            }
            group.samplePointIds().add(row.currentSamplePointId());
            return new Group(group.name(), group.contact(), group.regionName(),
                    group.longitude(), group.latitude(), group.recordCount() + 1,
                    group.samplePointIds());
        }));
        List<List<String>> values = new ArrayList<>();
        values.add(List.of("重复身份组", "样本点名称", "联系方式", "地区", "经度", "纬度",
                "业务记录数", "当前样本点数"));
        groups.forEach((id, group) -> values.add(List.of(id, group.name(), group.contact(),
                group.regionName(), text(group.longitude()), text(group.latitude()),
                Integer.toString(group.recordCount()), Integer.toString(group.samplePointIds().size()))));
        return worksheet(values, List.of(40, 22, 18, 20, 14, 14, 14, 14),
                true, false, 9, "landscape", 1);
    }

    private static String binding(UUID batchId) {
        return worksheet(List.of(
                List.of("工作簿用途", "导出批次"),
                List.of(PURPOSE, batchId.toString())), List.of(44, 38),
                false, false, 9, "landscape", 1);
    }

    private static String worksheet(
            List<List<String>> rows, List<Integer> widths, boolean freezeHeader,
            boolean editableDetail, int paperSize, String orientation, int fitToWidth) {
        StringBuilder sheetRows = new StringBuilder();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> values = rows.get(rowIndex);
            StringBuilder cells = new StringBuilder();
            for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                int style = rowIndex == 0 ? 1
                        : editableDetail ? (columnIndex <= 15
                                ? (columnIndex == 0 || columnIndex == 5 || columnIndex == 13
                                        || columnIndex == 14 ? 4 : 2)
                                : 3) : 0;
                cells.append("<c r=\"").append(column(columnIndex + 1)).append(rowIndex + 1)
                        .append("\" t=\"inlineStr\" s=\"").append(style)
                        .append("\"><is><t xml:space=\"preserve\">")
                        .append(xml(values.get(columnIndex))).append("</t></is></c>");
            }
            sheetRows.append("<row r=\"").append(rowIndex + 1).append("\" ht=\"")
                    .append(rowIndex == 0 ? 34 : editableDetail ? 26 : 28)
                    .append("\" customHeight=\"1\">").append(cells).append("</row>");
        }
        StringBuilder columns = new StringBuilder("<cols>");
        for (int index = 0; index < widths.size(); index++) {
            columns.append("<col min=\"").append(index + 1).append("\" max=\"")
                    .append(index + 1).append("\" width=\"").append(widths.get(index))
                    .append("\" customWidth=\"1\"/>");
        }
        columns.append("</cols>");
        String protection = editableDetail
                ? "<sheetProtection sheet=\"1\" objects=\"1\" scenarios=\"1\" "
                        + "formatCells=\"0\" formatColumns=\"0\" formatRows=\"0\"/>"
                : "";
        String validation = editableDetail
                ? "<dataValidations count=\"1\"><dataValidation type=\"list\" allowBlank=\"1\" "
                        + "showErrorMessage=\"1\" errorTitle=\"请选择处理方式\" "
                        + "error=\"只能选择归并、保留或暂不处理\" sqref=\"Q2:Q5001\">"
                        + "<formula1>&quot;" + MERGE + "," + KEEP_DISTINCT + "," + DEFER
                        + "&quot;</formula1></dataValidation></dataValidations>"
                : "";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>"
                + (freezeHeader ? "<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\">"
                        + "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                        + "</sheetView></sheetViews>"
                        : "<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"/></sheetViews>")
                + "<sheetFormatPr defaultRowHeight=\"22\"/>" + columns + "<sheetData>"
                + sheetRows + "</sheetData>" + protection
                + (freezeHeader ? "<autoFilter ref=\"A1:" + column(widths.size()) + "1\"/>" : "")
                + validation
                + "<pageMargins left=\"0.25\" right=\"0.25\" top=\"0.5\" bottom=\"0.5\" "
                + "header=\"0.2\" footer=\"0.2\"/>"
                + "<pageSetup paperSize=\"" + paperSize + "\" orientation=\"" + orientation
                + "\" fitToWidth=\"" + fitToWidth + "\" fitToHeight=\"0\"/></worksheet>";
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
                    <sheet name="身份重复明细" sheetId="2" r:id="rId2"/>
                    <sheet name="重复身份组" sheetId="3" r:id="rId3"/>
                    <sheet name="导出绑定" sheetId="4" state="hidden" r:id="rId4"/>
                  </sheets>
                  <definedNames><definedName name="_工作簿用途" hidden="1">&quot;SAMPLE-IDENTITY-GOVERNANCE-V1&quot;</definedName></definedNames>
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
            name.append((char) ('A' + (value - 1) % 26));
            value--;
        }
        return name.reverse().toString();
    }

    private static String identitySummary(Row row) {
        return row.sampleName() + "｜" + row.sampleContact() + "｜"
                + row.regionCode() + "｜" + text(row.longitude()) + "," + text(row.latitude());
    }

    private static String text(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String text(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static BigDecimal decimal(String value) {
        String normalized = value == null ? "" : value.trim().replace(",", "");
        if (normalized.isEmpty()) throw invalid();
        return new BigDecimal(normalized);
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_SAMPLE_IDENTITY_GOVERNANCE_WORKBOOK");
    }

    public record Row(
            String sourceRecordId,
            long sourceVersion,
            String sourceDomain,
            String productCode,
            String surveyPeriod,
            UUID currentSamplePointId,
            String sampleName,
            String sampleContact,
            String regionCode,
            String regionName,
            BigDecimal longitude,
            BigDecimal latitude,
            int approvedRecordCount,
            String duplicateIdentityGroup,
            String rowBinding,
            String action,
            UUID targetSamplePointId,
            String reviewBasis,
            String note) {
        public Row {
            sourceRecordId = required(sourceRecordId);
            if (sourceVersion < 0 || currentSamplePointId == null || longitude == null
                    || latitude == null || approvedRecordCount < 1) throw invalid();
            sourceDomain = required(sourceDomain).toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("PRODUCTION", "MARKET").contains(sourceDomain)) throw invalid();
            productCode = required(productCode);
            surveyPeriod = required(surveyPeriod);
            sampleName = required(sampleName);
            sampleContact = required(sampleContact);
            regionCode = required(regionCode);
            regionName = required(regionName);
            duplicateIdentityGroup = required(duplicateIdentityGroup);
            rowBinding = required(rowBinding);
            action = clean(action);
            reviewBasis = clean(reviewBasis);
            note = clean(note);
            if (!action.isEmpty() && !ACTIONS.contains(action)) throw invalid();
            if (MERGE.equals(action) && (targetSamplePointId == null || reviewBasis.isEmpty())) throw invalid();
            if ((KEEP_DISTINCT.equals(action) || DEFER.equals(action)) && targetSamplePointId != null) {
                throw invalid();
            }
            if (KEEP_DISTINCT.equals(action) && reviewBasis.isEmpty()) throw invalid();
        }
    }

    public record ParsedWorkbook(UUID batchId, List<Row> rows) {
        public ParsedWorkbook {
            rows = List.copyOf(rows);
        }
    }

    private record Group(
            String name, String contact, String regionName,
            BigDecimal longitude, BigDecimal latitude, int recordCount,
            java.util.LinkedHashSet<UUID> samplePointIds) {}

    private static String required(String value) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw invalid();
        return cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
