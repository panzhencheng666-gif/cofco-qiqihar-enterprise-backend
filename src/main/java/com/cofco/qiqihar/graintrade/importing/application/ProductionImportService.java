package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportPort;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
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
public class ProductionImportService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ImportJobRepository repository;
    private final ProductionImportPort production;
    private final AccessControl accessControl;
    private final BusinessAuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProductionImportService(ImportJobRepository repository, ProductionImportPort production,
            AccessControl accessControl, BusinessAuditRecorder audit, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.production = production;
        this.accessControl = accessControl;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String template() { return ProductionImportTemplate.csv(); }

    @Transactional
    public ImportJobView importCsv(String idempotencyKey, byte[] bytes) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        SecurityPrincipal principal = accessControl.require("BUSINESS_IMPORT", null);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String digest = digest(bytes);
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

    public ImportErrorFile errors(UUID importJobId) {
        SecurityPrincipal principal = accessControl.require("BUSINESS_IMPORT", null);
        ImportJob job = repository.findById(importJobId).orElseThrow(() -> new ClientRequestException(
                "IMPORT_JOB_NOT_FOUND", "Import job does not exist")).job();
        if (!job.requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_ERROR_FILE_NOT_ALLOWED", "Import job belongs to a different subject");
        }
        StringBuilder csv = new StringBuilder(String.join(",", ProductionImportTemplate.HEADERS))
                .append(",errorCode,errorMessage\n");
        job.rows().stream().filter(row -> row.outcomeCode().equals("ERROR")).forEach(row -> {
            ProductionImportTemplate.HEADERS.forEach(header -> csv.append(CsvTable.escape(row.values().get(header))).append(','));
            csv.append(CsvTable.escape(row.errorCode())).append(',').append(CsvTable.escape(row.errorMessage())).append('\n');
        });
        return new ImportErrorFile("production-import-errors-" + job.id() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ImportJob process(ImportJob reserved, String idempotencyKey, String content, String digest, UUID retryOf,
            Set<Integer> retryRows, SecurityPrincipal principal) {
        List<ParsedRow> rows = parse(content);
        if (retryRows != null) rows = rows.stream().filter(row -> retryRows.contains(row.number)).toList();
        rows.stream().filter(row -> row.error == null).map(row -> row.values.get("regionCode"))
                .distinct().forEach(region -> accessControl.require("BUSINESS_IMPORT", region));
        List<ImportRowOutcome> outcomes = new ArrayList<>();
        for (ParsedRow row : rows) {
            if (row.error != null) {
                outcomes.add(ImportRowOutcome.error(row.number, row.error.code, row.error.message, row.values));
                continue;
            }
            try {
                String recordId = production.importDraft(row.draft());
                outcomes.add(ImportRowOutcome.imported(row.number, recordId, row.values));
            } catch (ClientRequestException exception) {
                outcomes.add(ImportRowOutcome.error(row.number, exception.code(), exception.clientMessage(), row.values));
            }
        }
        var now = clock.instant();
        String status = outcomes.stream().anyMatch(row -> row.outcomeCode().equals("ERROR"))
                ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        ImportJob job = repository.complete(new ImportJob(reserved.id(), ProductionImportTemplate.DOMAIN, idempotencyKey,
                digest, principal.subjectId(), principal.workUnitCode(), retryOf, status,
                reserved.createdAt(), now, outcomes), content);
        audit.record(principal, "IMPORT_JOB", job.id().toString(), "IMPORT_JOB_COMPLETED", now,
                detail(job.importedRows(), job.failedRows()));
        return job;
    }

    private List<ParsedRow> parse(String content) {
        List<List<String>> table;
        try { table = CsvTable.parse(content, ProductionImportTemplate.HEADERS.size()); }
        catch (CsvTable.LimitExceededException exception) {
            throw new ClientRequestException(exception.code(), exception.getMessage());
        }
        catch (IllegalArgumentException exception) { throw new ClientRequestException("INVALID_IMPORT_CSV", "CSV syntax is invalid"); }
        if (table.isEmpty() || !table.getFirst().equals(ProductionImportTemplate.HEADERS)) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "CSV header does not match the current production template");
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (int index = 1; index < table.size(); index++) {
            List<String> cells = table.get(index);
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < ProductionImportTemplate.HEADERS.size(); column++) {
                values.put(ProductionImportTemplate.HEADERS.get(column), column < cells.size() ? cells.get(column).trim() : "");
            }
            if (values.values().stream().allMatch(String::isBlank)) {
                rows.add(ParsedRow.error(index + 1, values, "IMPORT_ROW_EMPTY", "Import row is empty"));
            } else {
                rows.add(toDraft(index + 1, values));
            }
        }
        if (rows.isEmpty()) throw new ClientRequestException("INVALID_IMPORT_CSV", "CSV must contain at least one data row");
        return List.copyOf(rows);
    }

    private static ParsedRow toDraft(int number, Map<String, String> values) {
        try {
            BoundedInput.requireMapText("IMPORT_ROW_VALUE_FORMAT", values);
            if (required(values, "productCode") || required(values, "objectTypeCode") || required(values, "regionCode")
                    || required(values, "surveyDate") || required(values, "cultivatedAreaMu")
                    || required(values, "yieldPerMuKilograms")
                    || ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.stream()
                            .anyMatch(header -> required(values, header))) {
                return ParsedRow.error(number, values, "IMPORT_ROW_REQUIRED_VALUE", "Required production import value is blank");
            }
            Map<String, String> submissionMetadata = new LinkedHashMap<>();
            ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.forEach(
                    header -> submissionMetadata.put(header, values.get(header)));
            PlainDecimal.parse(values.get("PROD_SAMPLE_LATITUDE"), 3, 7, "IMPORT_ROW_VALUE_FORMAT");
            PlainDecimal.parse(values.get("PROD_SAMPLE_LONGITUDE"), 3, 7, "IMPORT_ROW_VALUE_FORMAT");
            return ParsedRow.valid(number, values, new ProductionDraft(values.get("productCode"), values.get("objectTypeCode"),
                    values.get("regionCode"), emptyToNull(values.get("cultivarCode")), LocalDate.parse(values.get("surveyDate")),
                    PlainDecimal.parse(values.get("cultivatedAreaMu"), 14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    PlainDecimal.parse(values.get("yieldPerMuKilograms"), 14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    Map.of(), Map.of(), Map.of(), Map.of(), submissionMetadata));
        } catch (RuntimeException exception) {
            return ParsedRow.error(number, values, "IMPORT_ROW_VALUE_FORMAT", "Production date or decimal value is invalid");
        }
    }

    private static boolean required(Map<String, String> values, String name) { return values.get(name).isBlank(); }
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
