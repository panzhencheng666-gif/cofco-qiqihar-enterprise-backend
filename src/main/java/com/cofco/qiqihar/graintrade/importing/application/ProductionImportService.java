package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.importing.infrastructure.XlsxTable;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProductionImportService implements QueuedImportProcessor {
    private final ImportJobRepository repository;
    private final ProductionImportPort production;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final BusinessImportLimits limits;
    private final RegionImportResolver regions;
    private final BusinessImportTemplateCatalog templateCatalog;

    public ProductionImportService(ImportJobRepository repository, ProductionImportPort production,
            AccessControl accessControl, BusinessAuditRecorder audit, ObjectMapper objectMapper, Clock clock,
            BusinessImportLimits limits, RegionImportResolver regions,
            BusinessImportTemplateCatalog templateCatalog) {
        this.repository = repository;
        this.production = production;
        this.accessControl = accessControl;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.limits = limits;
        this.regions = regions;
        this.templateCatalog = templateCatalog;
    }

    public String template() { return ProductionImportTemplate.csv(); }

    public com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook.Template workbook(
            String productCode, String objectTypeCode) {
        accessControl.require("BUSINESS_IMPORT", null);
        return ProductionImportTemplate.workbook(
                production.importDefinition(productCode, objectTypeCode));
    }

    public com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook.Template productWorkbook(
            String productCode) {
        accessControl.require("BUSINESS_IMPORT", null);
        var objectTypes = templateCatalog.objectTypes(ProductionImportTemplate.DOMAIN, productCode);
        var definitions = objectTypes.stream()
                .map(option -> production.importDefinition(productCode, option.code())).toList();
        return ProductionImportTemplate.productWorkbook(productCode, definitions, objectTypes);
    }

    @Transactional
    public ImportJobView importFile(String idempotencyKey, String productCode, String objectTypeCode,
            String filename, String mediaType, byte[] bytes) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128
                || filename == null || filename.isBlank() || filename.length() > 255
                || bytes == null || bytes.length == 0 || bytes.length > limits.maximumBytes()) throw invalid();
        ImportMenuContext expectedContext = new ImportMenuContext(productCode, objectTypeCode);
        SecurityPrincipal principal = accessControl.require("BUSINESS_IMPORT", null);
        List<List<String>> table = table(filename, mediaType, bytes, expectedContext, limits.maximumRows());
        String content = canonicalContent(table);
        String digest = digest(bytes);
        if (limits.queued(table.size() - 1)) {
            return ImportJobView.from(repository.queue(principal.subjectId(), ProductionImportTemplate.DOMAIN,
                    idempotencyKey, digest, principal.workUnitCode(), null, content, clock.instant()).stored().job());
        }
        var reservation = repository.reserve(principal.subjectId(), ProductionImportTemplate.DOMAIN, idempotencyKey,
                digest, principal.workUnitCode(), clock.instant());
        if (!reservation.owner()) return ImportJobView.from(reservation.stored().job());
        return ImportJobView.from(process(reservation.stored().job(), idempotencyKey,
                content, digest, null, null, principal));
    }

    @Transactional
    public ImportJobView retry(UUID importJobId) {
        SecurityPrincipal principal = accessControl.require("BUSINESS_IMPORT", null);
        var prior = repository.findById(importJobId).orElseThrow(() -> new ClientRequestException(
                "IMPORT_JOB_NOT_FOUND", "Import job does not exist"));
        if (!prior.job().requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_RETRY_NOT_ALLOWED", "Import job belongs to a different subject");
        }
        if ("FAILED".equals(prior.job().statusCode())) {
            String retryKey = "retry-" + UUID.randomUUID();
            return ImportJobView.from(repository.queue(principal.subjectId(), ProductionImportTemplate.DOMAIN,
                    retryKey, prior.job().contentSha256(), principal.workUnitCode(), prior.job().id(),
                    prior.sourceContent(), clock.instant()).stored().job());
        }
        Set<Integer> failedRows = prior.job().rows().stream().filter(row -> row.outcomeCode().equals("ERROR"))
                .map(com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome::rowNumber).collect(java.util.stream.Collectors.toSet());
        if (failedRows.isEmpty()) {
            throw new ConflictException("IMPORT_RETRY_NOT_AVAILABLE", "Import job has no failed rows to retry");
        }
        String retryKey = "retry-" + UUID.randomUUID();
        var reservation = repository.reserve(principal.subjectId(), ProductionImportTemplate.DOMAIN, retryKey,
                prior.job().contentSha256(), principal.workUnitCode(), clock.instant());
        return ImportJobView.from(process(reservation.stored().job(), retryKey, prior.sourceContent(),
                prior.job().contentSha256(), prior.job().id(), failedRows, principal));
    }

    @Transactional
    public ImportErrorFile errors(UUID importJobId) {
        SecurityPrincipal principal = accessControl.require("BUSINESS_IMPORT", null);
        ImportJob job = repository.findById(importJobId).orElseThrow(() -> new ClientRequestException(
                "IMPORT_JOB_NOT_FOUND", "Import job does not exist")).job();
        if (!job.requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_ERROR_FILE_NOT_ALLOWED", "Import job belongs to a different subject");
        }
        List<String> headers = job.rows().isEmpty()
                ? ProductionImportTemplate.HEADERS
                : job.rows().getFirst().values().keySet().stream().sorted().toList();
        StringBuilder csv = new StringBuilder(String.join(",", headers))
                .append(",errorCode,errorMessage\n");
        job.rows().stream().filter(row -> row.outcomeCode().equals("ERROR")).forEach(row -> {
            headers.forEach(header -> csv.append(CsvTable.escape(row.values().get(header))).append(','));
            csv.append(CsvTable.escape(row.errorCode())).append(',').append(CsvTable.escape(row.errorMessage())).append('\n');
        });
        ImportErrorFile file = new ImportErrorFile("production-import-errors-" + job.id() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        audit.record(principal, "IMPORT_JOB", job.id().toString(), "IMPORT_ERROR_FILE_DOWNLOADED",
                clock.instant(), "{}");
        return file;
    }

    @Transactional(readOnly = true)
    public ImportJobView status(UUID importJobId) {
        SecurityPrincipal principal = accessControl.require("BUSINESS_IMPORT", null);
        return ImportJobView.from(owned(importJobId, principal).job());
    }

    @Override public String domainCode() { return ProductionImportTemplate.DOMAIN; }

    @Override
    @Transactional
    public void processQueued(UUID jobId, SecurityPrincipal principal) {
        var stored = owned(jobId, principal);
        if (!"PROCESSING".equals(stored.job().statusCode())) {
            throw new ConflictException("IMPORT_JOB_NOT_PROCESSING", "Import job is not processing");
        }
        process(stored.job(), stored.job().idempotencyKey(), stored.sourceContent(),
                stored.job().contentSha256(), stored.job().retryOf(), null, principal);
    }

    private ImportJobRepository.StoredImportJob owned(UUID jobId, SecurityPrincipal principal) {
        var stored = repository.findById(jobId)
                .filter(value -> value.job().domainCode().equals(ProductionImportTemplate.DOMAIN))
                .orElseThrow(() -> new ClientRequestException("IMPORT_JOB_NOT_FOUND", "Import job does not exist"));
        if (!stored.job().requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_JOB_NOT_ALLOWED", "Import job belongs to a different subject");
        }
        return stored;
    }

    private ImportJob process(ImportJob reserved, String idempotencyKey, String content, String digest, UUID retryOf,
            Set<Integer> retryRows, SecurityPrincipal principal) {
        List<ParsedRow> rows = parse(content);
        if (retryRows != null) rows = rows.stream().filter(row -> retryRows.contains(row.number)).toList();
        rows.stream().filter(row -> row.error == null).map(row -> row.values.get("regionCode"))
                .distinct().forEach(region -> accessControl.require("BUSINESS_IMPORT", region));
        List<ParsedRow> validated = rows.stream().map(this::validate).toList();
        boolean hasErrors = validated.stream().anyMatch(row -> row.error != null);
        List<ImportRowOutcome> outcomes = new ArrayList<>(validated.size());
        if (hasErrors) {
            validated.forEach(row -> outcomes.add(row.error == null
                    ? ImportRowOutcome.error(row.number, "NOT_IMPORTED_ATOMIC_BATCH",
                            "Another row failed; the atomic batch was not written", row.values)
                    : ImportRowOutcome.error(row.number, row.error.code, row.error.message, row.values)));
        } else {
            for (ParsedRow row : validated) {
                String recordId = production.importDraft(row.draft());
                outcomes.add(ImportRowOutcome.imported(row.number, recordId, row.values));
            }
        }
        var now = clock.instant();
        String status = hasErrors ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        ImportJob job = repository.complete(new ImportJob(reserved.id(), ProductionImportTemplate.DOMAIN, idempotencyKey,
                digest, principal.subjectId(), principal.workUnitCode(), retryOf, status,
                reserved.createdAt(), now, outcomes, reserved.startedAt(), reserved.attemptCount(), null, null,
                reserved.leaseToken(), reserved.leaseUntil()), content);
        audit.record(principal, "IMPORT_JOB", job.id().toString(), "IMPORT_JOB_COMPLETED", now,
                detail(job.importedRows(), job.failedRows()));
        return job;
    }

    private ParsedRow validate(ParsedRow row) {
        if (row.error != null) return row;
        try {
            production.validateImportDraft(row.draft());
            return row;
        } catch (ClientRequestException exception) {
            return ParsedRow.error(row.number, row.values, exception.code(), exception.clientMessage());
        } catch (ConflictException exception) {
            return ParsedRow.error(row.number, row.values, exception.code(), exception.clientMessage());
        } catch (ResourceNotFoundException exception) {
            return ParsedRow.error(row.number, row.values, exception.code(), exception.clientMessage());
        }
    }

    private List<List<String>> table(String filename, String mediaType, byte[] bytes,
            ImportMenuContext expectedContext, int maxDataRows) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv") && (mediaType == null || mediaType.equals("text/csv")
                || mediaType.equals("application/csv") || mediaType.equals("application/vnd.ms-excel"))) {
            try {
                List<List<String>> table = CsvTable.parse(
                        new String(bytes, StandardCharsets.UTF_8), ProductionImportTemplate.HEADERS.size(),
                        maxDataRows);
                requireCsvContext(table, expectedContext);
                return table;
            } catch (CsvTable.LimitExceededException exception) {
                throw new ClientRequestException(exception.code(), exception.getMessage());
            } catch (IllegalArgumentException exception) {
                throw new ClientRequestException("INVALID_IMPORT_CSV", "CSV syntax is invalid");
            }
        }
        if (lower.endsWith(".xlsx") && (mediaType == null || mediaType.equals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))) {
            com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook.Context context = null;
            try {
                context = com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook
                        .context(bytes, ProductionImportTemplate.DOMAIN);
                expectedContext.requireMatches(context.productCode(), context.objectTypeCode());
                return ProductionImportTemplate.canonicalXlsx(bytes,
                        production.importDefinition(expectedContext.productCode(), expectedContext.objectTypeCode()),
                        maxDataRows);
            } catch (ClientRequestException exception) {
                throw exception;
            } catch (IllegalArgumentException exception) {
                if (context != null && context.contractVersion() != null) {
                    String code = exception.getMessage() != null
                                    && exception.getMessage().startsWith("XLSX_CONTRACT_MISMATCH")
                            ? "IMPORT_CONTRACT_MISMATCH" : "INVALID_IMPORT_FORMAT";
                    throw new ClientRequestException(code,
                            "XLSX fields or values do not match the current production survey contract");
                }
                try {
                    List<List<String>> table = XlsxTable.parseWorksheet(
                            bytes, 1, ProductionImportTemplate.HEADERS.size(), maxDataRows + 1);
                    requireCsvContext(table, expectedContext);
                    return table;
                } catch (IllegalArgumentException legacyException) {
                    throw new ClientRequestException("INVALID_IMPORT_FORMAT", "XLSX import file is invalid");
                }
            }
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

    private static String canonicalContent(List<List<String>> table) {
        StringBuilder csv = new StringBuilder();
        table.forEach(row -> {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) csv.append(',');
                csv.append(CsvTable.escape(row.get(index)));
            }
            csv.append('\n');
        });
        return csv.toString();
    }

    private List<ParsedRow> parse(String content) {
        List<List<String>> table;
        int lineEnd = content.indexOf('\n');
        if (lineEnd < 0) throw new ClientRequestException(
                "INVALID_IMPORT_TEMPLATE", "Import file does not contain a header row");
        List<String> headers = List.of(content.substring(0, lineEnd).split(",", -1));
        try { table = CsvTable.parse(content, headers.size(), limits.maximumRows()); }
        catch (CsvTable.LimitExceededException exception) {
            throw new ClientRequestException(exception.code(), exception.getMessage());
        }
        catch (IllegalArgumentException exception) { throw new ClientRequestException("INVALID_IMPORT_CSV", "CSV syntax is invalid"); }
        if (table.isEmpty() || !table.getFirst().equals(headers)) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "CSV header does not match the current production template");
        }
        boolean legacy = headers.equals(ProductionImportTemplate.HEADERS)
                || headers.equals(ProductionImportTemplate.XLSX_CANONICAL_HEADERS);
        ProductionImportDefinition definition = null;
        if (!legacy) {
            if (table.size() < 2 || headers.size() < 5
                    || !headers.subList(0, 2).equals(List.of("productCode", "objectTypeCode"))) {
                throw new ClientRequestException(
                        "INVALID_IMPORT_TEMPLATE", "Workbook fields do not match the current production form");
            }
            definition = production.importDefinition(
                    table.get(1).get(0).trim(), table.get(1).get(1).trim());
            List<String> expected = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("productCode", "objectTypeCode"),
                    ProductionImportTemplate.codes(definition).stream()).toList();
            if (!headers.equals(expected)) {
                throw new ClientRequestException(
                        "INVALID_IMPORT_TEMPLATE", "Workbook fields do not match the current production form");
            }
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (int index = 1; index < table.size(); index++) {
            List<String> cells = table.get(index);
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                values.put(headers.get(column), column < cells.size() ? cells.get(column).trim() : "");
            }
            if (values.values().stream().allMatch(String::isBlank)) {
                rows.add(ParsedRow.error(index + 1, values, "IMPORT_ROW_EMPTY", "Import row is empty"));
            } else {
                rows.add(definition == null
                        ? toDraft(index + 1, values)
                        : toDraft(index + 1, values, definition));
            }
        }
        if (rows.isEmpty()) throw new ClientRequestException("INVALID_IMPORT_CSV", "CSV must contain at least one data row");
        return List.copyOf(rows);
    }

    private ParsedRow toDraft(
            int number, Map<String, String> values, ProductionImportDefinition definition) {
        try {
            if (!definition.productCode().equals(values.get("productCode"))
                    || !definition.objectTypeCode().equals(values.get("objectTypeCode"))) {
                return ParsedRow.error(number, values,
                        "IMPORT_ROW_CONTEXT_MISMATCH", "Workbook row context is inconsistent");
            }
            BoundedInput.requireMapText("IMPORT_ROW_VALUE_FORMAT", values);
            if (required(values, "regionCode") || required(values, "surveyYear")
                    || required(values, "cultivatedAreaMu")
                    || required(values, "yieldPerMuKilograms")
                    || ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.stream()
                            .filter(header -> !header.equals("PROD_REPORTER_NAME"))
                            .anyMatch(header -> required(values, header))) {
                return ParsedRow.error(number, values,
                        "IMPORT_ROW_REQUIRED_VALUE", "Required production import value is blank");
            }
            String regionCode = regions.resolve(values.get("regionCode"));
            values.put("regionCode", regionCode);
            Map<String, String> submissionMetadata = new LinkedHashMap<>();
            ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.forEach(
                    header -> submissionMetadata.put(header, values.getOrDefault(header, "")));
            ProductionImportTemplate.DETAIL_HEADERS.forEach(
                    header -> putIfPresent(submissionMetadata, header, values));
            PlainDecimal.parse(values.get("PROD_SAMPLE_LATITUDE"), 3, 7, "IMPORT_ROW_VALUE_FORMAT");
            PlainDecimal.parse(values.get("PROD_SAMPLE_LONGITUDE"), 3, 7, "IMPORT_ROW_VALUE_FORMAT");
            Map<String, BigDecimal> quality = new LinkedHashMap<>();
            Map<String, BigDecimal> costs = new LinkedHashMap<>();
            Map<String, BigDecimal> insurance = new LinkedHashMap<>();
            Map<String, BigDecimal> subsidies = new LinkedHashMap<>();
            for (ProductionImportDefinition.Group group : definition.groups()) {
                if (group.code().equals("DETAIL")) continue;
                Map<String, BigDecimal> target = switch (group.code()) {
                    case "QUALITY" -> quality;
                    case "COST" -> costs;
                    case "INSURANCE" -> insurance;
                    case "SUBSIDY" -> subsidies;
                    default -> throw new ClientRequestException(
                            "INVALID_IMPORT_TEMPLATE", "Unsupported production field group");
                };
                group.fields().forEach(field -> {
                    String value = values.get(field.code());
                    if (value != null && !value.isBlank()) {
                        target.put(field.code(), PlainDecimal.parse(value,
                                field.precision() - field.scale(), field.scale(),
                                "IMPORT_ROW_VALUE_FORMAT"));
                    }
                });
            }
            return ParsedRow.valid(number, values, new ProductionDraft(
                    definition.productCode(), definition.objectTypeCode(), regionCode,
                    null, surveyDate(values),
                    PlainDecimal.parse(values.get("cultivatedAreaMu"), 14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    PlainDecimal.parse(values.get("yieldPerMuKilograms"), 14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    Map.copyOf(quality), Map.copyOf(costs), Map.copyOf(insurance), Map.copyOf(subsidies),
                    submissionMetadata, evidenceIds(values), Integer.parseInt(values.get("surveyYear")),
                    values.get("surveyMonth") == null || values.get("surveyMonth").isBlank()
                            ? null : Integer.parseInt(values.get("surveyMonth"))));
        } catch (ClientRequestException exception) {
            return ParsedRow.error(number, values, exception.code(), exception.clientMessage());
        } catch (RuntimeException exception) {
            return ParsedRow.error(number, values,
                    "IMPORT_ROW_VALUE_FORMAT", "Production date or decimal value is invalid");
        }
    }

    private ParsedRow toDraft(int number, Map<String, String> values) {
        try {
            BoundedInput.requireMapText("IMPORT_ROW_VALUE_FORMAT", values);
            if (required(values, "productCode") || required(values, "objectTypeCode") || required(values, "regionCode")
                    || required(values, "surveyDate") || required(values, "cultivatedAreaMu")
                    || required(values, "yieldPerMuKilograms") || required(values, "evidencePhotoId")
                    || ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.stream()
                            .filter(header -> !header.equals("PROD_REPORTER_NAME"))
                            .anyMatch(header -> required(values, header))) {
                return ParsedRow.error(number, values, "IMPORT_ROW_REQUIRED_VALUE", "Required production import value is blank");
            }
            String regionCode = regions.resolve(values.get("regionCode"));
            values.put("regionCode", regionCode);
            Map<String, String> submissionMetadata = new LinkedHashMap<>();
            ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.forEach(
                    header -> submissionMetadata.put(header, values.get(header)));
            ProductionImportTemplate.DETAIL_HEADERS.forEach(header -> putIfPresent(submissionMetadata, header, values));
            PlainDecimal.parse(values.get("PROD_SAMPLE_LATITUDE"), 3, 7, "IMPORT_ROW_VALUE_FORMAT");
            PlainDecimal.parse(values.get("PROD_SAMPLE_LONGITUDE"), 3, 7, "IMPORT_ROW_VALUE_FORMAT");
            return ParsedRow.valid(number, values, new ProductionDraft(values.get("productCode"), values.get("objectTypeCode"),
                    regionCode, null, LocalDate.parse(values.get("surveyDate")),
                    PlainDecimal.parse(values.get("cultivatedAreaMu"), 14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    PlainDecimal.parse(values.get("yieldPerMuKilograms"), 14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    decimalValues(values, ProductionImportTemplate.QUALITY_HEADERS),
                    decimalValues(values, ProductionImportTemplate.COST_HEADERS),
                    decimalValues(values, List.of("INSURANCE_AMOUNT")),
                    decimalValues(values, List.of("SUBSIDY_AMOUNT")), submissionMetadata,
                    List.of(UUID.fromString(values.get("evidencePhotoId")))));
        } catch (ClientRequestException exception) {
            return ParsedRow.error(number, values, exception.code(), exception.clientMessage());
        } catch (RuntimeException exception) {
            return ParsedRow.error(number, values, "IMPORT_ROW_VALUE_FORMAT", "Production date or decimal value is invalid");
        }
    }

    private static boolean required(Map<String, String> values, String name) {
        String value = values.get(name);
        return value == null || value.isBlank();
    }
    private static LocalDate surveyDate(Map<String, String> values) {
        int year = Integer.parseInt(values.get("surveyYear"));
        String monthValue = values.get("surveyMonth");
        int month = monthValue == null || monthValue.isBlank() ? 1 : Integer.parseInt(monthValue);
        if (year < 1900 || year > 2200 || month < 1 || month > 12) {
            throw new IllegalArgumentException("invalid survey period");
        }
        return LocalDate.of(year, month, 1);
    }
    private static List<UUID> evidenceIds(Map<String, String> values) {
        String evidence = values.get("evidencePhotoId");
        return evidence == null || evidence.isBlank() ? List.of() : List.of(UUID.fromString(evidence));
    }
    private static void putIfPresent(Map<String, String> target, String code, Map<String, String> values) {
        String value = values.get(code);
        if (value != null && !value.isBlank()) target.put(code, value);
    }
    private static Map<String, BigDecimal> decimalValues(Map<String, String> values, List<String> headers) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        headers.forEach(header -> {
            String value = values.get(header);
            if (value != null && !value.isBlank()) {
                result.put(header, PlainDecimal.parse(value, 14, 4, "IMPORT_ROW_VALUE_FORMAT"));
            }
        });
        return Map.copyOf(result);
    }
    private static String emptyToNull(String value) { return value.isBlank() ? null : value; }
    private static ClientRequestException invalid() { return new ClientRequestException("INVALID_IMPORT_REQUEST", "Import request is invalid"); }
    private static String detail(int imported, int failed) { return "{\"importedRows\":" + imported + ",\"failedRows\":" + failed + "}"; }
    private static String digest(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record ParsedRow(int number, Map<String, String> values, ProductionDraft draft, RowError error) {
        static ParsedRow valid(int number, Map<String, String> values, ProductionDraft draft) { return new ParsedRow(number, Map.copyOf(values), draft, null); }
        static ParsedRow error(int number, Map<String, String> values, String code, String message) { return new ParsedRow(number, Map.copyOf(values), null, new RowError(code, message)); }
    }
    private record RowError(String code, String message) {}
}
