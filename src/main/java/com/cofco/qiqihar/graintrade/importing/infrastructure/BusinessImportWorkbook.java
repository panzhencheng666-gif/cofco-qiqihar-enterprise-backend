package com.cofco.qiqihar.graintrade.importing.infrastructure;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates the common business-only XLSX protocol used by every import domain. */
public final class BusinessImportWorkbook {
    public static final String CONTRACT_VERSION = "2026.08.17-2";
    public static final String PHOTO_FILENAMES_CODE = "evidencePhotoNames";
    public static final String PHOTO_FILENAMES_LABEL = "现场照片文件名（可选，最多5张，分号分隔）";
    private static final String DOMAIN_METADATA_NAME = "_业务模板校验_业务类型";
    private static final String PRODUCT_METADATA_NAME = "_业务模板校验_产品品种";
    private static final String OBJECT_METADATA_NAME = "_业务模板校验_对象类型";
    private static final String VERSION_METADATA_NAME = "_业务模板校验_校验代号";
    private static final String DIGEST_METADATA_NAME = "_业务模板校验_校验摘要";
    private static final String PURPOSE_METADATA_NAME = "_业务模板校验_工作簿用途";
    private static final List<String> CONTRACT_METADATA_NAMES = List.of(
            DOMAIN_METADATA_NAME, PRODUCT_METADATA_NAME, OBJECT_METADATA_NAME,
            VERSION_METADATA_NAME, DIGEST_METADATA_NAME);
    private static final Set<String> COORDINATE_CODES = Set.of(
            "PROD_SAMPLE_LATITUDE", "PROD_SAMPLE_LONGITUDE",
            "MKT_SAMPLE_LATITUDE", "MKT_SAMPLE_LONGITUDE",
            "LOG_SAMPLE_LATITUDE", "LOG_SAMPLE_LONGITUDE");
    private static final Map<String, String> PUBLIC_CONTEXT_VALUES = Map.ofEntries(
            Map.entry("PRODUCTION", "产情"), Map.entry("MARKET", "市场"), Map.entry("LOGISTICS", "物流"),
            Map.entry("DESIGN_SAMPLE_POINT", "设计样本点"),
            Map.entry("FORMAL_SAMPLE_POINT", "正式样本"),
            Map.entry("CORN", "玉米"), Map.entry("SOYBEAN", "大豆"), Map.entry("RICE", "稻谷"),
            Map.entry("FARMER", "农户"), Map.entry("VILLAGE_COMMITTEE", "村委会"),
            Map.entry("AGRICULTURAL_TECH_STATION", "农技站"), Map.entry("TRADER", "贸易商"),
            Map.entry("DEEP_PROCESSOR", "深加工"), Map.entry("WHOLESALE_MARKET", "批发市场"),
            Map.entry("RESERVE_ENTERPRISE", "承储企业"), Map.entry("RICE_MILL", "米厂"),
            Map.entry("BREEDING_FACTORY", "养殖厂"), Map.entry("FEED_MILL", "饲料厂"),
            Map.entry("AGRICULTURAL_INPUT_STORE", "农资店"),
            Map.entry("RAIL_NODE", "铁路站点"), Map.entry("ROAD_NODE", "公路物流节点"),
            Map.entry("ROUTE_EVENT", "物流业务记录"));
    private static final Map<String, String> INTERNAL_CONTEXT_VALUES = reverse(PUBLIC_CONTEXT_VALUES);

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
                           String contractVersion, String contractDigest, List<String> headers, List<String> labels,
                           List<ColumnRule> rules) {
        public Template {
            domainCode = required(domainCode);
            domainLabel = required(domainLabel);
            productCode = optional(productCode);
            objectTypeCode = optional(objectTypeCode);
            if ((productCode == null && objectTypeCode != null)
                    || (productCode == null && requiresProductContext(domainCode))) {
                throw new IllegalArgumentException("INVALID_TEMPLATE_CONTEXT");
            }
            headers = List.copyOf(headers);
            labels = List.copyOf(labels);
            rules = rules == null ? List.of() : List.copyOf(rules);
            if (headers.isEmpty() || headers.size() != labels.size() || headers.stream().anyMatch(String::isBlank)
                    || labels.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("INVALID_TEMPLATE");
            if (!rules.isEmpty() && (rules.size() != headers.size()
                    || !rules.stream().map(ColumnRule::code).toList().equals(headers))) {
                throw new IllegalArgumentException("INVALID_TEMPLATE_RULES");
            }
            contractVersion = contractVersion == null || contractVersion.isBlank()
                    ? CONTRACT_VERSION : contractVersion.trim();
            contractDigest = contractDigest == null || contractDigest.isBlank()
                    ? digest(domainCode, productCode, objectTypeCode, contractVersion, headers, labels, rules)
                    : contractDigest.trim();
        }

        public Template(String domainCode, String domainLabel, String productCode, String objectTypeCode,
                String contractVersion, List<String> headers, List<String> labels, List<ColumnRule> rules) {
            this(domainCode, domainLabel, productCode, objectTypeCode, contractVersion, null,
                    headers, labels, rules);
        }

        public Template(String domainCode, String domainLabel, String productCode, String objectTypeCode,
                List<String> headers, List<String> labels) {
            this(domainCode, domainLabel, productCode, objectTypeCode, null, null, headers, labels, List.of());
        }

        private static String required(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("INVALID_TEMPLATE_CONTEXT");
            return value.trim();
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static boolean requiresProductContext(String domainCode) {
            return !Set.of("LOGISTICS", "DESIGN_SAMPLE_POINT", "FORMAL_SAMPLE_POINT")
                    .contains(domainCode);
        }
    }

    public record WorkbookOptions(String dataSheetName, String purposeCode, String purposeLabel,
                                  Set<String> hiddenColumnCodes,
                                  List<List<String>> additionalInstructions) {
        public WorkbookOptions {
            if (dataSheetName == null || dataSheetName.isBlank() || dataSheetName.length() > 31
                    || dataSheetName.matches(".*[\\\\/?*:\\[\\]].*")) {
                throw new IllegalArgumentException("INVALID_WORKBOOK_OPTIONS");
            }
            dataSheetName = dataSheetName.trim();
            purposeCode = optional(purposeCode);
            purposeLabel = optional(purposeLabel);
            if ((purposeCode == null) != (purposeLabel == null)) {
                throw new IllegalArgumentException("INVALID_WORKBOOK_OPTIONS");
            }
            hiddenColumnCodes = hiddenColumnCodes == null ? Set.of() : Set.copyOf(hiddenColumnCodes);
            if (hiddenColumnCodes.stream().anyMatch(code -> code == null || code.isBlank())) {
                throw new IllegalArgumentException("INVALID_WORKBOOK_OPTIONS");
            }
            additionalInstructions = additionalInstructions == null ? List.of()
                    : additionalInstructions.stream().map(row -> {
                        if (row == null || row.size() != 2 || row.stream().anyMatch(value -> value == null || value.isBlank())) {
                            throw new IllegalArgumentException("INVALID_WORKBOOK_OPTIONS");
                        }
                        return List.copyOf(row);
                    }).toList();
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private BusinessImportWorkbook() {}

    /** Returns the business-facing Chinese label for a template context code. */
    public static String businessLabel(String internalValue) {
        return publicContextValue(internalValue);
    }

    public static byte[] create(Template template) {
        return create(template, List.of());
    }

    public static ColumnRule photoFilenameRule(String code) {
        return new ColumnRule(code, "TEXT", "PHOTO_FILENAMES", false, List.of(), 0, 0,
                "可留空；有照片时填写最多 5 个文件名，中文或英文分号分隔");
    }

    public static byte[] create(Template template, List<List<String>> dataRows) {
        return create(template, dataRows, new WorkbookOptions(
                template.domainLabel() + "填报", null, null, Set.of(), List.of()));
    }

    public static byte[] create(
            Template template, List<List<String>> dataRows, WorkbookOptions options) {
        List<List<String>> safeRows = dataRows == null ? List.of() : dataRows.stream().map(List::copyOf).toList();
        if (safeRows.size() > 5_000 || safeRows.stream().anyMatch(row -> row.size() != template.headers().size())) {
            throw new IllegalArgumentException("INVALID_TEMPLATE_ROWS");
        }
        if (options == null || !template.headers().containsAll(options.hiddenColumnCodes())) {
            throw new IllegalArgumentException("INVALID_WORKBOOK_OPTIONS");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", packageRelationships());
                entry(zip, "xl/workbook.xml", workbook(template, options));
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
                entry(zip, "xl/styles.xml", styles());
                entry(zip, "xl/worksheets/sheet1.xml", dataSheet(template, safeRows, options));
                entry(zip, "xl/worksheets/sheet2.xml", instructionSheet(template, options));
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("XLSX_TEMPLATE_GENERATION_FAILED", exception);
        }
    }

    public record ImportSheet(String productCode, String objectTypeCode,
            List<List<String>> rows, List<List<String>> submittedRows) {
        public ImportSheet(String productCode, String objectTypeCode, List<List<String>> rows) {
            this(productCode, objectTypeCode, rows, rows);
        }

        public ImportSheet {
            rows = List.copyOf(rows);
            submittedRows = List.copyOf(submittedRows);
            if (rows.size() != submittedRows.size()) {
                throw new IllegalArgumentException("INVALID_IMPORT_ROW_PROVENANCE");
            }
        }
    }

    public record Context(String productCode, String objectTypeCode, String contractVersion, String contractDigest) {}

    public record WorkbookSheet(String name, Template template, List<List<String>> rows) {
        public WorkbookSheet {
            if (name == null || name.isBlank() || name.length() > 31
                    || name.matches(".*[\\\\/?*:\\[\\]].*")) {
                throw new IllegalArgumentException("INVALID_WORKBOOK_SHEET");
            }
            name = name.trim();
            rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        }

        public WorkbookSheet(String name, Template template) {
            this(name, template, List.of());
        }
    }

    public record LocatedImportSheet(String name, ImportSheet sheet, Template template) {}

    public static byte[] createSheets(List<WorkbookSheet> sheets) {
        List<WorkbookSheet> safeSheets = validateSheets(sheets);
        Template contract = multiSheetContract(safeSheets);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes(safeSheets.size() + 1));
                entry(zip, "_rels/.rels", packageRelationships());
                entry(zip, "xl/workbook.xml", multiWorkbook(safeSheets, contract));
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(safeSheets.size() + 1));
                entry(zip, "xl/styles.xml", styles());
                for (int index = 0; index < safeSheets.size(); index++) {
                    WorkbookSheet sheet = safeSheets.get(index);
                    entry(zip, "xl/worksheets/sheet" + (index + 1) + ".xml",
                            dataSheet(sheet.template(), sheet.rows(), new WorkbookOptions(
                                    sheet.name(), null, null, Set.of(), List.of())));
                }
                entry(zip, "xl/worksheets/sheet" + (safeSheets.size() + 1) + ".xml",
                        multiInstructionSheet(safeSheets));
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("XLSX_TEMPLATE_GENERATION_FAILED", exception);
        }
    }

    public static List<LocatedImportSheet> readDraftSheets(
            byte[] bytes, List<WorkbookSheet> expectedSheets, int maxDataRows) {
        List<WorkbookSheet> safeSheets = validateSheets(expectedSheets);
        Template contract = multiSheetContract(safeSheets);
        List<String> expectedNames = java.util.stream.Stream.concat(
                safeSheets.stream().map(WorkbookSheet::name), java.util.stream.Stream.of("填报说明")).toList();
        if (!XlsxTable.parseWorksheetNames(bytes).equals(expectedNames)) {
            throw new IllegalArgumentException("XLSX_SHEET_IDENTITY_MISMATCH");
        }
        Context context = context(bytes, contract.domainCode());
        if (!java.util.Objects.equals(contract.productCode(), context.productCode())
                || context.objectTypeCode() != null
                || !contract.contractVersion().equals(context.contractVersion())
                || !contract.contractDigest().equals(context.contractDigest())) {
            throw new IllegalArgumentException("XLSX_CONTRACT_MISMATCH");
        }
        Map<String, String> instructions = instructionContext(bytes, safeSheets.size() + 1);
        List<LocatedImportSheet> result = new ArrayList<>();
        int totalRows = 0;
        for (int index = 0; index < safeSheets.size(); index++) {
            WorkbookSheet expected = safeSheets.get(index);
            String mapping = instructions.get("工作表" + (index + 1));
            String expectedMapping = expected.name() + "｜" + publicContextValue(expected.template().objectTypeCode());
            if (!expectedMapping.equals(mapping)) {
                throw new IllegalArgumentException("XLSX_SHEET_IDENTITY_MISMATCH");
            }
            Template template = expected.template();
            List<List<String>> sheet = XlsxTable.parseWorksheet(
                    bytes, index + 1, template.headers().size(), maxDataRows + 1);
            if (sheet.isEmpty() || !sheet.getFirst().equals(template.labels())) {
                throw new IllegalArgumentException("INVALID_XLSX_TEMPLATE");
            }
            List<List<String>> submittedRows = boundedDataRows(sheet, 1, maxDataRows);
            totalRows = Math.addExact(totalRows, submittedRows.size());
            if (totalRows > maxDataRows) throw new IllegalArgumentException("INVALID_XLSX");
            result.add(new LocatedImportSheet(expected.name(), new ImportSheet(
                    contract.productCode(), template.objectTypeCode(),
                    normalizeRows(submittedRows, template.rules()), submittedRows), template));
        }
        return List.copyOf(result);
    }

    public static String purpose(byte[] bytes) {
        try {
            return XlsxTable.parseDefinedNames(bytes, List.of(PURPOSE_METADATA_NAME))
                    .get(PURPOSE_METADATA_NAME);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("INVALID_XLSX_PURPOSE", exception);
        }
    }

    public static Context context(byte[] bytes, String domainCode) {
        Map<String, String> visibleContext = instructionContext(bytes);
        String domainLabel = visibleContext.get("填报类别");
        if (blank(domainLabel)) domainLabel = visibleContext.get("业务类型");
        if (blank(domainLabel)) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        }
        String actualDomain = internalContextValue(domainLabel);
        if (!domainCode.equals(actualDomain)) throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        boolean productMissing = blank(visibleContext.get("产品品种"));
        boolean objectTypeMissing = blank(visibleContext.get("对象类型"));
        if ((productMissing && !objectTypeMissing)
                || (productMissing && requiresProductContext(domainCode))) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        }
        String productCode = productMissing ? null : internalContextValue(visibleContext.get("产品品种"));
        String objectTypeCode = objectTypeMissing ? null : internalContextValue(visibleContext.get("对象类型"));
        Map<String, String> machineContext = contractContext(bytes);
        if (!actualDomain.equals(machineContext.get(DOMAIN_METADATA_NAME))
                || !java.util.Objects.equals(productCode, optionalMetadata(machineContext.get(PRODUCT_METADATA_NAME)))
                || !java.util.Objects.equals(objectTypeCode, optionalMetadata(machineContext.get(OBJECT_METADATA_NAME)))) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        }
        String contractVersion = machineContext.get(VERSION_METADATA_NAME);
        String contractDigest = machineContext.get(DIGEST_METADATA_NAME);
        if (blank(contractVersion) || blank(contractDigest) || !contractDigest.startsWith("sha256:")) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTRACT");
        }
        return new Context(productCode, objectTypeCode, contractVersion.trim(), contractDigest.trim());
    }

    private static boolean requiresProductContext(String domainCode) {
        return !Set.of("LOGISTICS", "DESIGN_SAMPLE_POINT", "FORMAL_SAMPLE_POINT")
                .contains(domainCode);
    }

    public static ImportSheet read(byte[] bytes, String domainCode, List<String> headers, List<String> labels) {
        return read(bytes, domainCode, headers, labels, 5_000);
    }

    public static ImportSheet read(byte[] bytes, String domainCode, List<String> headers, List<String> labels,
            int maxDataRows) {
        Context context = context(bytes, domainCode);
        List<List<String>> sheet = XlsxTable.parseWorksheet(bytes, 1, headers.size(), maxDataRows + 2);
        if (sheet.isEmpty() || !sheet.getFirst().equals(labels)) {
            throw new IllegalArgumentException("INVALID_XLSX_TEMPLATE");
        }
        int firstDataRow = legacyInternalHeaderRow(sheet, headers) ? 2 : 1;
        return new ImportSheet(context.productCode(), context.objectTypeCode(),
                boundedDataRows(sheet, firstDataRow, maxDataRows));
    }

    public static ImportSheet read(byte[] bytes, Template template) {
        return read(bytes, template, 5_000);
    }

    public static ImportSheet read(byte[] bytes, Template template, int maxDataRows) {
        ImportSheet sheet = readDraft(bytes, template, maxDataRows);
        validateRows(sheet.rows(), template.rules());
        return sheet;
    }

    /** Reads each product-workbook row independently; row validation is performed by the draft importer. */
    public static ImportSheet readDraft(byte[] bytes, Template template, int maxDataRows) {
        return readDraft(bytes, template, List.of(), maxDataRows);
    }

    /**
     * Reads a current workbook or one of the explicitly enumerated prior contracts. Prior contracts must
     * have the same business context and columns; parsed rows are always normalized against current rules.
     */
    public static ImportSheet readDraft(byte[] bytes, Template template,
            List<Template> compatiblePriorTemplates, int maxDataRows) {
        Context context = context(bytes, template.domainCode());
        if (!java.util.Objects.equals(template.productCode(), context.productCode())
                || !java.util.Objects.equals(template.objectTypeCode(), context.objectTypeCode())) {
            throw new IllegalArgumentException("XLSX_CONTEXT_MISMATCH");
        }
        List<Template> compatible = compatiblePriorTemplates == null ? List.of() : compatiblePriorTemplates;
        if (compatible.stream().anyMatch(candidate ->
                !template.domainCode().equals(candidate.domainCode())
                        || !java.util.Objects.equals(template.productCode(), candidate.productCode())
                        || !java.util.Objects.equals(template.objectTypeCode(), candidate.objectTypeCode())
                        || !template.headers().equals(candidate.headers())
                        || !template.labels().equals(candidate.labels()))) {
            throw new IllegalArgumentException("INVALID_COMPATIBLE_XLSX_CONTRACT");
        }
        boolean currentContract = template.contractVersion().equals(context.contractVersion())
                && template.contractDigest().equals(context.contractDigest());
        boolean priorContract = compatible.stream().anyMatch(candidate ->
                candidate.contractVersion().equals(context.contractVersion())
                        && candidate.contractDigest().equals(context.contractDigest()));
        if (!currentContract && !priorContract) {
            throw new IllegalArgumentException("XLSX_CONTRACT_MISMATCH");
        }
        List<List<String>> sheet = XlsxTable.parseWorksheet(
                bytes, 1, template.headers().size(), maxDataRows + 2);
        if (sheet.isEmpty() || !sheet.getFirst().equals(template.labels())) {
            throw new IllegalArgumentException("INVALID_XLSX_TEMPLATE");
        }
        int firstDataRow = legacyInternalHeaderRow(sheet, template.headers()) ? 2 : 1;
        List<List<String>> submittedRows = boundedDataRows(sheet, firstDataRow, maxDataRows);
        List<List<String>> rows = normalizeRows(submittedRows, template.rules());
        return new ImportSheet(context.productCode(), context.objectTypeCode(), rows, submittedRows);
    }

    private static boolean legacyInternalHeaderRow(List<List<String>> sheet, List<String> headers) {
        return sheet.size() > 1 && sheet.get(1).equals(headers);
    }

    private static List<List<String>> boundedDataRows(
            List<List<String>> sheet, int firstDataRow, int maxDataRows) {
        List<List<String>> rows = sheet.subList(firstDataRow, sheet.size()).stream()
                .filter(row -> row.stream().anyMatch(value -> !value.isBlank()))
                .map(List::copyOf).toList();
        if (rows.size() > maxDataRows) throw new IllegalArgumentException("INVALID_XLSX");
        return rows;
    }

    private static List<List<String>> normalizeRows(List<List<String>> rows, List<ColumnRule> rules) {
        if (rules.isEmpty()) return rows;
        return rows.stream().map(row -> {
            ArrayList<String> values = new ArrayList<>(row);
            for (int index = 0; index < rules.size(); index++) {
                if (!values.get(index).isBlank()) {
                    values.set(index, normalizeCell(values.get(index), rules.get(index)));
                }
            }
            return List.copyOf(values);
        }).toList();
    }

    /** Normalizes common human spreadsheet notation without changing the business value. */
    public static String normalizeCell(String value, ColumnRule rule) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
        if ("DATE".equals(rule.valueType())) return normalizeExcelDate(normalized);
        if (!"DECIMAL".equals(rule.valueType())) return normalized;
        String decimalText = normalizeSubmittedDecimal(normalized);
        if (isCoordinateRule(rule)) {
            return normalizeCoordinate(decimalText, rule.scale());
        }
        return normalizeDecimalScale(decimalText, rule.scale());
    }

    /** Preserves submitted precision while accepting the same human spreadsheet notation as validation. */
    public static String normalizeSubmittedDecimal(String value) {
        if (value == null) return null;
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .replace(",", "")
                .replace(" ", "")
                .replace("\t", "")
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replaceFirst("(元/吨|公斤/亩|千克/亩|吨|公斤|千克|元|%)$", "");
    }

    private static String normalizeCoordinate(String value, int storageScale) {
        try {
            BigDecimal coordinate = new BigDecimal(value);
            if (storageScale >= 0 && coordinate.scale() > storageScale) {
                coordinate = coordinate.setScale(storageScale, RoundingMode.HALF_UP);
            }
            return coordinate.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return value;
        }
    }

    private static String normalizeDecimalScale(String value, int allowedScale) {
        if (allowedScale < 0) return value;
        try {
            BigDecimal decimal = new BigDecimal(value);
            return decimal.setScale(allowedScale, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return value;
        }
    }

    private static String normalizeExcelDate(String value) {
        try {
            return LocalDate.parse(value).toString();
        } catch (RuntimeException ignored) {
            String humanDate = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
                    .replace('年', '-').replace('月', '-').replace("日", "")
                    .replace('/', '-').replace('.', '-');
            try {
                return LocalDate.parse(humanDate,
                        java.time.format.DateTimeFormatter.ofPattern("uuuu-M-d")).toString();
            } catch (RuntimeException invalidHumanDate) {
                // Continue with Excel's serial-date notation.
            }
            try {
                long days = new BigDecimal(value).longValueExact();
                return LocalDate.of(1899, 12, 30).plusDays(days).toString();
            } catch (RuntimeException invalidExcelDate) {
                return value;
            }
        }
    }

    private static Map<String, String> instructionContext(byte[] bytes) {
        List<String> names = XlsxTable.parseWorksheetNames(bytes);
        int instructionNumber = names.indexOf("填报说明") + 1;
        if (instructionNumber == 0 || instructionNumber != names.size()) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        }
        return instructionContext(bytes, instructionNumber);
    }

    private static Map<String, String> instructionContext(byte[] bytes, int worksheetNumber) {
        List<List<String>> instructions = XlsxTable.parseWorksheet(bytes, worksheetNumber, 2);
        Map<String, String> context = new LinkedHashMap<>();
        instructions.forEach(row -> context.put(row.getFirst().trim(), row.get(1).trim()));
        return context;
    }

    private static List<WorkbookSheet> validateSheets(List<WorkbookSheet> sheets) {
        if (sheets == null || sheets.isEmpty() || sheets.size() > 30) {
            throw new IllegalArgumentException("INVALID_WORKBOOK_SHEETS");
        }
        List<WorkbookSheet> safe = List.copyOf(sheets);
        Template first = safe.getFirst().template();
        if (first == null || first.productCode() == null || first.objectTypeCode() == null
                || safe.stream().anyMatch(sheet -> sheet.template() == null
                        || !first.domainCode().equals(sheet.template().domainCode())
                        || !first.productCode().equals(sheet.template().productCode())
                        || sheet.template().objectTypeCode() == null
                        || sheet.rows().size() > 5_000
                        || sheet.rows().stream().anyMatch(row -> row.size() != sheet.template().headers().size()))
                || safe.stream().map(WorkbookSheet::name).distinct().count() != safe.size()
                || safe.stream().map(sheet -> sheet.template().objectTypeCode()).distinct().count() != safe.size()) {
            throw new IllegalArgumentException("INVALID_WORKBOOK_SHEETS");
        }
        return safe;
    }

    private static Template multiSheetContract(List<WorkbookSheet> sheets) {
        Template first = sheets.getFirst().template();
        List<String> identities = sheets.stream()
                .map(sheet -> sheet.name() + "|" + sheet.template().objectTypeCode()
                        + "|" + sheet.template().contractDigest()).toList();
        return new Template(first.domainCode(), first.domainLabel(), first.productCode(), null,
                CONTRACT_VERSION + "-multi", identities, identities, List.of());
    }

    private static Map<String, String> contractContext(byte[] bytes) {
        try {
            return XlsxTable.parseDefinedNames(bytes, CONTRACT_METADATA_NAMES);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("INVALID_XLSX_CONTRACT", exception);
        }
    }

    private static String optionalMetadata(String value) {
        return blank(value) ? null : value;
    }

    private static String dataSheet(
            Template template, List<List<String>> dataRows, WorkbookOptions options) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < dataRows.size(); index++) {
            rows.append(dataRow(index + 2, dataRows.get(index), template.rules()));
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
                """.formatted(columns(template, options.hiddenColumnCodes()), row(1, template.labels(), 1, false),
                rows, columnName(template.headers().size()),
                dataValidations(template));
    }

    private static String instructionSheet(Template template, WorkbookOptions options) {
        java.util.ArrayList<List<String>> metadata = new java.util.ArrayList<>(List.of(
                List.of("填报类别", publicContextValue(template.domainCode()))));
        if (template.productCode() != null) {
            metadata.add(List.of("产品品种", publicContextValue(template.productCode())));
        }
        if (template.objectTypeCode() != null) {
            metadata.add(List.of("对象类型", publicContextValue(template.objectTypeCode())));
        }
        if (options.purposeCode() != null) {
            metadata.add(List.of("工作簿用途", options.purposeLabel()));
        }
        StringBuilder xml = new StringBuilder();
        for (int index = 0; index < metadata.size(); index++) {
            xml.append(row(index + 1, metadata.get(index), 0, false));
        }
        java.util.ArrayList<List<String>> instructions = new java.util.ArrayList<>(List.of(
                List.of("填报说明", "请按业务字段名称填写，不得修改表头"),
                List.of("填报人", "由登录账号自动记录，不得在模板中填写"),
                List.of("现场照片", "可选，最多 5 张；没有照片不影响导入、提交和审核")));
        if ("PRODUCTION".equals(template.domainCode()) && template.headers().contains("regionCode")) {
            instructions.add(List.of("所在地区",
                    "请填写完整行政区划路径，如“齐齐哈尔市 / 梅里斯达斡尔族区 / 雅尔塞镇 / 音钦村”；旧模板可继续填写有效地区代码"));
        }
        instructions.add(List.of("处理方式", "5000 条以内即时处理；5001 至 50000 条转入后台任务处理"));
        instructions.addAll(options.additionalInstructions());
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

    private static String multiInstructionSheet(List<WorkbookSheet> sheets) {
        Template first = sheets.getFirst().template();
        List<List<String>> metadata = new ArrayList<>();
        metadata.add(List.of("填报类别", publicContextValue(first.domainCode())));
        metadata.add(List.of("产品品种", publicContextValue(first.productCode())));
        for (int index = 0; index < sheets.size(); index++) {
            WorkbookSheet sheet = sheets.get(index);
            metadata.add(List.of("工作表" + (index + 1),
                    sheet.name() + "｜" + publicContextValue(sheet.template().objectTypeCode())));
        }
        metadata.add(List.of("填报说明", "每个工作表已锁定对象类型，请勿修改工作表名称或表头"));
        metadata.add(List.of("重复字段", "年度、月份、名称、地区等共同字段需在各对象工作表分别填写"));
        metadata.add(List.of("填报人", "由登录账号自动记录，不得在模板中填写"));
        metadata.add(List.of("现场照片", "可选，最多 5 张；没有照片不影响导入、提交和审核"));
        metadata.add(List.of("处理方式", "全部工作表合计 5000 条以内即时处理；5001 至 50000 条转入后台任务处理"));
        StringBuilder xml = new StringBuilder();
        for (int index = 0; index < metadata.size(); index++) {
            xml.append(row(index + 1, metadata.get(index), index == sheets.size() + 2 ? 1 : 0, false));
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <cols><col min="1" max="1" width="20" customWidth="1"/><col min="2" max="2" width="56" customWidth="1"/></cols>
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

    private static String columns(Template template, Set<String> hiddenColumnCodes) {
        if (template.rules().isEmpty() && hiddenColumnCodes.isEmpty()) {
            return "<col min=\"1\" max=\"" + template.headers().size()
                    + "\" width=\"20\" customWidth=\"1\"/>";
        }
        StringBuilder columns = new StringBuilder();
        for (int index = 0; index < template.headers().size(); index++) {
            columns.append("<col min=\"").append(index + 1).append("\" max=\"").append(index + 1)
                    .append("\" width=\"20\" customWidth=\"1\"");
            if (!template.rules().isEmpty()) {
                columns.append(" style=\"").append(style(template.rules().get(index))).append("\"");
            }
            if (hiddenColumnCodes.contains(template.headers().get(index))) {
                columns.append(" hidden=\"1\"");
            }
            columns.append("/>");
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

    private static String workbook(Template template, WorkbookOptions options) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="%s" sheetId="1" r:id="rId1"/><sheet name="填报说明" sheetId="2" r:id="rId2"/></sheets>
                  %s
                </workbook>
                """.formatted(xml(options.dataSheetName()), contractMetadata(template, options));
    }

    private static String multiWorkbook(List<WorkbookSheet> sheets, Template contract) {
        StringBuilder worksheetEntries = new StringBuilder();
        for (int index = 0; index < sheets.size(); index++) {
            worksheetEntries.append("<sheet name=\"").append(xml(sheets.get(index).name()))
                    .append("\" sheetId=\"").append(index + 1).append("\" r:id=\"rId")
                    .append(index + 1).append("\"/>");
        }
        int instructionIndex = sheets.size() + 1;
        worksheetEntries.append("<sheet name=\"填报说明\" sheetId=\"").append(instructionIndex)
                .append("\" r:id=\"rId").append(instructionIndex).append("\"/>");
        WorkbookOptions options = new WorkbookOptions("填报说明", null, null, Set.of(), List.of());
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>%s</sheets>
                  %s
                </workbook>
                """.formatted(worksheetEntries, contractMetadata(contract, options));
    }

    private static String contractMetadata(Template template, WorkbookOptions options) {
        String standard = """
                <definedNames>
                  <definedName name="%s" hidden="1">&quot;%s&quot;</definedName>
                  <definedName name="%s" hidden="1">&quot;%s&quot;</definedName>
                  <definedName name="%s" hidden="1">&quot;%s&quot;</definedName>
                  <definedName name="%s" hidden="1">&quot;%s&quot;</definedName>
                  <definedName name="%s" hidden="1">&quot;%s&quot;</definedName>%s
                </definedNames>
                """.formatted(DOMAIN_METADATA_NAME, xml(template.domainCode()),
                PRODUCT_METADATA_NAME, xml(template.productCode() == null ? "" : template.productCode()),
                OBJECT_METADATA_NAME, xml(template.objectTypeCode() == null ? "" : template.objectTypeCode()),
                VERSION_METADATA_NAME, xml(template.contractVersion()),
                DIGEST_METADATA_NAME, xml(template.contractDigest()),
                options.purposeCode() == null ? "" : "\n                  <definedName name=\""
                        + PURPOSE_METADATA_NAME + "\" hidden=\"1\">&quot;"
                        + xml(options.purposeCode()) + "&quot;</definedName>");
        return standard;
    }

    private static String publicContextValue(String internalValue) {
        String value = PUBLIC_CONTEXT_VALUES.get(internalValue);
        if (value == null) throw new IllegalArgumentException("INVALID_PUBLIC_XLSX_CONTEXT");
        return value;
    }

    private static String internalContextValue(String publicValue) {
        String value = INTERNAL_CONTEXT_VALUES.get(publicValue);
        if (value == null) throw new IllegalArgumentException("INVALID_XLSX_CONTEXT");
        return value;
    }

    private static Map<String, String> reverse(Map<String, String> values) {
        Map<String, String> reversed = new LinkedHashMap<>();
        values.forEach((internal, business) -> {
            if (reversed.put(business, internal) != null) throw new IllegalStateException("DUPLICATE_XLSX_CONTEXT");
        });
        return Map.copyOf(reversed);
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

    private static String contentTypes(int worksheetCount) {
        StringBuilder worksheets = new StringBuilder();
        for (int index = 1; index <= worksheetCount; index++) {
            worksheets.append("  <Override PartName=\"/xl/worksheets/sheet").append(index)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                %s  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>
                """.formatted(worksheets);
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

    private static String workbookRelationships(int worksheetCount) {
        StringBuilder relationships = new StringBuilder();
        for (int index = 1; index <= worksheetCount; index++) {
            relationships.append("  <Relationship Id=\"rId").append(index)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(index).append(".xml\"/>\n");
        }
        relationships.append("  <Relationship Id=\"rId").append(worksheetCount + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n");
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                %s</Relationships>
                """.formatted(relationships);
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
            String prompt = rule.required() ? "必填；" + validationHint(rule) : validationHint(rule);
            validations.append("<dataValidation showInputMessage=\"1\" showErrorMessage=\"1\" errorStyle=\"stop\"")
                    .append(" allowBlank=\"").append(rule.required() ? "0" : "1").append("\"")
                    .append(" promptTitle=\"").append(xml(promptTitle)).append("\"")
                    .append(" prompt=\"").append(xml(prompt)).append("\"")
                    .append(" errorTitle=\"字段校验失败\" error=\"").append(xml(prompt)).append("\"");
            validations.append(validationAttributes(rule)).append(" sqref=\"")
                    .append(column).append("2:").append(column).append("5001\">")
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
            return "<formula1>LEN(TRIM(" + column + "2))&gt;0</formula1>";
        }
        return "";
    }

    /** Returns the same business-facing rule used by XLSX prompts and server-side row errors. */
    public static String validationHint(ColumnRule rule) {
        if (isCoordinateRule(rule)) {
            return isLatitudeRule(rule)
                    ? "数字，范围 -90 至 90；超出存储精度时自动规范化"
                    : "数字，范围 -180 至 180；超出存储精度时自动规范化";
        }
        String base = switch (rule.valueType()) {
            case "DECIMAL" -> "数字，精度 " + rule.precision() + "，小数位不超过 " + rule.scale();
            case "DATE" -> "日期格式 YYYY-MM-DD";
            case "UUID" -> "UUID 格式";
            default -> rule.options().isEmpty() ? "文本" : "请从受控选项中选择";
        };
        return rule.description() == null || rule.description().isBlank()
                ? base : base + "；" + rule.description();
    }

    private static boolean isCoordinateRule(ColumnRule rule) {
        return COORDINATE_CODES.contains(rule.code())
                || rule.code().contains("纬度") || rule.code().contains("经度");
    }

    private static boolean isLatitudeRule(ColumnRule rule) {
        return rule.code().endsWith("LATITUDE") || rule.code().contains("纬度");
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
                                "XLSX_REQUIRED_VALUE: row " + (rowIndex + 2) + ", field " + rule.code());
                    }
                    continue;
                }
                validateValue(value, rule, rowIndex + 2);
            }
        }
    }

    /** Validates one non-blank business cell against the downloaded workbook contract. */
    public static void validateCell(String value, ColumnRule rule) {
        if (value == null || value.isBlank()) return;
        validateValue(normalizeCell(value, rule), rule, 0);
    }

    private static String digest(String domainCode, String productCode, String objectTypeCode,
            String contractVersion, List<String> headers, List<String> labels, List<ColumnRule> rules) {
        StringBuilder canonical = new StringBuilder(domainCode).append('\u001f')
                .append(productCode == null ? "" : productCode).append('\u001f')
                .append(objectTypeCode == null ? "" : objectTypeCode).append('\u001f')
                .append(contractVersion).append('\u001e')
                .append(String.join("\u001f", headers)).append('\u001e')
                .append(String.join("\u001f", labels));
        for (ColumnRule rule : rules) {
            canonical.append('\u001e').append(rule.code()).append('\u001f')
                    .append(rule.valueType()).append('\u001f').append(rule.controlType()).append('\u001f')
                    .append(rule.required()).append('\u001f').append(String.join("\u001d", rule.options()))
                    .append('\u001f').append(rule.precision()).append('\u001f').append(rule.scale())
                    .append('\u001f').append(rule.description() == null ? "" : rule.description());
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
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
