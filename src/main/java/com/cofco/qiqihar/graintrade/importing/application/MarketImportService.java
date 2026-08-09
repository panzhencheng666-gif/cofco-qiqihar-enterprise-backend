package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketImportService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ImportJobRepository jobs;
    private final MarketImportPort market;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public MarketImportService(ImportJobRepository jobs, MarketImportPort market,
            AccessControl access, BusinessAuditRecorder audit, Clock clock) {
        this.jobs = jobs;
        this.market = market;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public String template() { return MarketImportTemplate.csv(); }

    public com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook.Template workbook(
            String productCode, String objectTypeCode) {
        access.require("BUSINESS_IMPORT", null);
        return MarketImportTemplate.workbook(market.definition(productCode, objectTypeCode));
    }

    public ImportErrorFile errors(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        ImportJob job = jobs.findById(importJobId)
                .filter(stored -> stored.job().domainCode().equals(MarketImportTemplate.DOMAIN))
                .orElseThrow(() -> new ClientRequestException("IMPORT_JOB_NOT_FOUND", "Import job does not exist"))
                .job();
        if (!job.requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_ERROR_FILE_NOT_ALLOWED", "Import job belongs to a different subject");
        }
        List<String> headers = job.rows().isEmpty()
                ? MarketImportTemplate.HEADERS
                : job.rows().getFirst().values().keySet().stream().sorted().toList();
        StringBuilder csv = new StringBuilder(String.join(",", headers))
                .append(",errorCode,errorMessage\n");
        job.rows().stream().filter(row -> row.outcomeCode().equals("ERROR")).forEach(row -> {
            headers.forEach(header -> csv.append(CsvTable.escape(row.values().get(header))).append(','));
            csv.append(CsvTable.escape(row.errorCode())).append(',')
                    .append(CsvTable.escape(row.errorMessage())).append('\n');
        });
        return new ImportErrorFile("market-import-errors-" + job.id() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public ImportJobView importFile(String key, String productCode, String objectTypeCode,
            String filename, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128 || filename == null || filename.isBlank()
                || filename.length() > 255 || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        ImportMenuContext expectedContext = new ImportMenuContext(productCode, objectTypeCode);
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        String content = canonical(table(filename, mediaType, bytes, expectedContext));
        String digest = digest(bytes);
        var reservation = jobs.reserve(principal.subjectId(), MarketImportTemplate.DOMAIN, key, digest,
                principal.workUnitCode(), clock.instant());
        if (!reservation.owner()) return ImportJobView.from(reservation.stored().job());
        return process(reservation.stored().job(), key, content, digest, null, principal);
    }

    @Transactional
    public ImportJobView retry(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        var prior = jobs.findById(importJobId)
                .filter(stored -> stored.job().domainCode().equals(MarketImportTemplate.DOMAIN))
                .orElseThrow(() -> new ClientRequestException("IMPORT_JOB_NOT_FOUND", "Import job does not exist"));
        if (!prior.job().requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_RETRY_NOT_ALLOWED", "Import job belongs to a different subject");
        }
        if (prior.job().failedRows() == 0) {
            throw new ConflictException("IMPORT_RETRY_NOT_AVAILABLE", "Import job has no failed rows to retry");
        }
        String key = "retry-" + UUID.randomUUID();
        var reservation = jobs.reserve(principal.subjectId(), MarketImportTemplate.DOMAIN, key,
                prior.job().contentSha256(), principal.workUnitCode(), clock.instant());
        return process(reservation.stored().job(), key, prior.sourceContent(),
                prior.job().contentSha256(), prior.job().id(), principal);
    }

    private ImportJobView process(ImportJob reserved, String key, String content, String digest,
            UUID retryOf, SecurityPrincipal principal) {
        List<Row> rows = rows(content).stream().map(this::validate).toList();
        List<ImportRowOutcome> outcomes = new ArrayList<>();
        boolean hasErrors = rows.stream().anyMatch(row -> row.errorCode != null);
        if (hasErrors) {
            rows.forEach(row -> outcomes.add(row.errorCode == null
                    ? ImportRowOutcome.error(row.number, "NOT_IMPORTED_ATOMIC_BATCH",
                            "Another row failed; the atomic batch was not written", row.values)
                    : ImportRowOutcome.error(row.number, row.errorCode, row.errorMessage, row.values)));
        } else {
            for (Row row : rows) {
                String id = market.importRow(row.draft);
                outcomes.add(ImportRowOutcome.imported(row.number, id, row.values));
            }
        }
        var now = clock.instant();
        ImportJob job = jobs.complete(new ImportJob(reserved.id(), MarketImportTemplate.DOMAIN,
                key, digest, principal.subjectId(), principal.workUnitCode(), retryOf,
                hasErrors ? "COMPLETED_WITH_ERRORS" : "COMPLETED",
                reserved.createdAt(), now, outcomes), content);
        audit.record(principal, "IMPORT_JOB", job.id().toString(), "IMPORT_JOB_COMPLETED", now,
                "{\"importedRows\":" + job.importedRows() + ",\"failedRows\":" + job.failedRows() + "}");
        return ImportJobView.from(job);
    }

    private List<List<String>> table(String filename, String mediaType, byte[] bytes,
            ImportMenuContext expectedContext) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        try {
            if (lower.endsWith(".csv") && (mediaType == null || mediaType.equals("text/csv")
                    || mediaType.equals("application/csv") || mediaType.equals("application/vnd.ms-excel"))) {
                List<List<String>> table = CsvTable.parse(
                        new String(bytes, StandardCharsets.UTF_8), MarketImportTemplate.HEADERS.size());
                requireCsvContext(table, expectedContext);
                return table;
            }
            if (lower.endsWith(".xlsx") && (mediaType == null || mediaType.equals(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))) {
                var context = com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook
                        .context(bytes, MarketImportTemplate.DOMAIN);
                expectedContext.requireMatches(context.productCode(), context.objectTypeCode());
                return MarketImportTemplate.canonicalXlsx(bytes,
                        market.definition(expectedContext.productCode(), expectedContext.objectTypeCode()));
            }
        } catch (CsvTable.LimitExceededException exception) {
            throw new ClientRequestException(exception.code(), exception.getMessage());
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ClientRequestException("INVALID_IMPORT_FORMAT", "Import file is invalid");
        }
        throw new ClientRequestException("INVALID_IMPORT_FORMAT", "Import file format is not supported");
    }

    private static void requireCsvContext(List<List<String>> table, ImportMenuContext expectedContext) {
        if (table.size() < 2 || table.getFirst().size() < 2
                || !"productCode".equals(table.getFirst().get(0))
                || !"objectTypeCode".equals(table.getFirst().get(1))) return;
        table.stream().skip(1)
                .filter(row -> row.stream().anyMatch(value -> !value.isBlank()))
                .forEach(row -> expectedContext.requireMatches(row.get(0), row.get(1)));
    }

    private static String canonical(List<List<String>> table) {
        StringBuilder value = new StringBuilder();
        table.forEach(row -> { for (int index = 0; index < row.size(); index++) { if (index > 0) value.append(','); value.append(CsvTable.escape(row.get(index))); } value.append('\n'); });
        return value.toString();
    }

    private List<Row> rows(String content) {
        int lineEnd = content.indexOf('\n');
        if (lineEnd < 0) throw new ClientRequestException(
                "INVALID_IMPORT_TEMPLATE", "Import file does not contain a header row");
        int columns = content.substring(0, lineEnd).split(",", -1).length;
        List<List<String>> table = CsvTable.parse(content, columns);
        if (table.isEmpty()) throw new ClientRequestException(
                "INVALID_IMPORT_TEMPLATE", "Import file does not contain a header row");
        if (!table.getFirst().equals(MarketImportTemplate.HEADERS)) {
            return dynamicRows(table);
        }
        List<Row> rows = new ArrayList<>();
        for (int index = 1; index < table.size(); index++) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < MarketImportTemplate.HEADERS.size(); column++)
                values.put(MarketImportTemplate.HEADERS.get(column), table.get(index).get(column).trim());
            if (values.values().stream().allMatch(String::isBlank))
                throw new ClientRequestException("IMPORT_ROW_EMPTY", "Import row is empty");
            try {
                rows.add(Row.valid(index + 1, Map.copyOf(values), draft(values)));
            } catch (ClientRequestException exception) {
                rows.add(Row.error(index + 1, Map.copyOf(values), exception.code(), exception.clientMessage()));
            }
        }
        if (rows.isEmpty()) throw new ClientRequestException("INVALID_IMPORT_CSV", "CSV must contain at least one data row");
        return List.copyOf(rows);
    }

    private List<Row> dynamicRows(List<List<String>> table) {
        if (table.size() < 2 || table.getFirst().size() < 4
                || !table.getFirst().get(0).equals("productCode")
                || !table.getFirst().get(1).equals("objectTypeCode")) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_TEMPLATE", "Workbook fields do not match the current market form");
        }
        String productCode = table.get(1).get(0).trim();
        String objectTypeCode = table.get(1).get(1).trim();
        MarketImportDefinition definition = market.definition(productCode, objectTypeCode);
        List<String> expected = java.util.stream.Stream.concat(
                java.util.stream.Stream.of("productCode", "objectTypeCode"),
                MarketImportTemplate.workbook(definition).headers().stream()).toList();
        if (!table.getFirst().equals(expected)) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_TEMPLATE", "Workbook fields do not match the current market form");
        }
        List<Row> rows = new ArrayList<>();
        for (int index = 1; index < table.size(); index++) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < expected.size(); column++) {
                values.put(expected.get(column), table.get(index).get(column).trim());
            }
            if (!productCode.equals(values.get("productCode"))
                    || !objectTypeCode.equals(values.get("objectTypeCode"))) {
                rows.add(Row.error(index + 1, Map.copyOf(values),
                        "IMPORT_ROW_CONTEXT_MISMATCH", "Workbook row context is inconsistent"));
                continue;
            }
            try {
                rows.add(Row.valid(index + 1, Map.copyOf(values), dynamicDraft(values, definition)));
            } catch (ClientRequestException exception) {
                rows.add(Row.error(index + 1, Map.copyOf(values),
                        exception.code(), exception.clientMessage()));
            }
        }
        if (rows.isEmpty()) throw new ClientRequestException(
                "INVALID_IMPORT_XLSX", "Workbook must contain at least one data row");
        return List.copyOf(rows);
    }

    private static MarketImportRow dynamicDraft(
            Map<String, String> values, MarketImportDefinition definition) {
        try {
            Map<String, String> core = new LinkedHashMap<>();
            core.put("MKT_OBJECT_TYPE", definition.objectTypeCode());
            definition.coreFields().stream()
                    .filter(field -> !field.readOnly())
                    .filter(field -> !field.code().equals("MKT_OBJECT_TYPE"))
                    .filter(field -> !field.code().equals("MKT_REPORTER_NAME"))
                    .forEach(field -> {
                        String value = values.get(field.code());
                        if (value != null && !value.isBlank()) core.put(field.code(), value);
                    });
            Map<String, BigDecimal> facts = new LinkedHashMap<>();
            definition.factFields().forEach(field -> {
                String value = values.get(field.code());
                if (value != null && !value.isBlank()) {
                    facts.put(field.code(), PlainDecimal.parse(value,
                            field.precision() - field.scale(), field.scale(),
                            "IMPORT_ROW_VALUE_FORMAT"));
                }
            });
            String evidence = values.get(MarketImportTemplate.EVIDENCE_PHOTO_ID);
            return new MarketImportRow(definition.productCode(), core, facts,
                    List.of(UUID.fromString(evidence)));
        } catch (RuntimeException exception) {
            throw new ClientRequestException(
                    "IMPORT_ROW_VALUE_FORMAT", "Market import row is invalid");
        }
    }

    private static MarketImportRow draft(Map<String, String> value) {
        try {
            Map<String, String> core = new LinkedHashMap<>();
            core.put("MKT_OBJECT_TYPE", value.get("objectTypeCode")); core.put("MKT_REGION", value.get("regionCode"));
            core.put("MKT_TRADE_DATE", value.get("tradeDate")); core.put("MKT_TRADE_DIRECTION", value.get("tradeDirection"));
            core.put("MKT_PURCHASE_BASE_PRICE", value.get("purchaseBasePrice")); core.put("MKT_SALE_BASE_PRICE", blankToNull(value.get("saleBasePrice")));
            core.put("MKT_CARRIAGE_BOARD_AMOUNT", value.get("carriageBoardAmount")); core.put("MKT_PACKAGING_AMOUNT", value.get("packagingAmount"));
            core.put("MKT_FREIGHT_AMOUNT", value.get("freightAmount")); core.put("MKT_PACKAGING_FORM", value.get("packagingForm"));
            core.put("MKT_REPORTER_NAME", value.get("reporterName")); core.put("MKT_REPORTER_PHONE", value.get("reporterPhone"));
            core.put("MKT_SAMPLE_NAME", value.get("sampleName")); core.put("MKT_SAMPLE_CONTACT", value.get("sampleContact"));
            core.put("MKT_SAMPLE_LATITUDE", value.get("latitude")); core.put("MKT_SAMPLE_LONGITUDE", value.get("longitude"));
            Map<String, BigDecimal> facts = Map.of(
                    "PURCHASE_VOLUME", PlainDecimal.parse(value.get("purchaseVolume"), 14, 4,
                            "IMPORT_ROW_VALUE_FORMAT"),
                    "MOISTURE", PlainDecimal.parse(value.get("moisture"), 17, 1,
                            "IMPORT_ROW_VALUE_FORMAT"));
            return new MarketImportRow(value.get("productCode"), core, facts,
                    List.of(UUID.fromString(value.get("evidencePhotoId"))));
        } catch (RuntimeException exception) { throw new ClientRequestException("IMPORT_ROW_VALUE_FORMAT", "Market import row is invalid"); }
    }

    private Row validate(Row row) {
        if (row.errorCode != null) return row;
        try {
            market.validate(row.draft);
            return row;
        } catch (ClientRequestException exception) {
            return Row.error(row.number, row.values, exception.code(), exception.clientMessage());
        } catch (ConflictException exception) {
            return Row.error(row.number, row.values, exception.code(), exception.clientMessage());
        } catch (ResourceNotFoundException exception) {
            return Row.error(row.number, row.values, exception.code(), exception.clientMessage());
        }
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_IMPORT_REQUEST", "Import request is invalid"); }
    private static String digest(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private record Row(int number, Map<String, String> values, MarketImportRow draft,
            String errorCode, String errorMessage) {
        static Row valid(int number, Map<String, String> values, MarketImportRow draft) {
            return new Row(number, values, draft, null, null);
        }

        static Row error(int number, Map<String, String> values, String code, String message) {
            return new Row(number, values, null, code, message);
        }
    }
}
