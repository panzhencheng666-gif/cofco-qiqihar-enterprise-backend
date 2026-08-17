package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportRow;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
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
public class LogisticsImportService implements QueuedImportProcessor {
    private static final String SOURCE_PREFIX = "LOGISTICS-V1:";
    private static final java.util.Set<String> SYSTEM_GENERATED_CODES = java.util.Set.of(
            "fillingDate", "LOG_REPORTER", "LOG_STATUS");
    private final ImportJobRepository jobs;
    private final LogisticsImportPort logistics;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final Clock clock;
    private final BusinessImportLimits limits;

    public LogisticsImportService(ImportJobRepository jobs, LogisticsImportPort logistics,
            AccessControl access, BusinessAuditRecorder audit, Clock clock, BusinessImportLimits limits) {
        this.jobs = jobs;
        this.logistics = logistics;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.limits = limits;
    }

    public BusinessImportWorkbook.Template template(String productCode) {
        access.require("BUSINESS_IMPORT", null);
        return LogisticsImportTemplate.workbook(productCode, definition(productCode));
    }

    @Transactional
    public ImportErrorFile errors(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        ImportJob job = ownedJob(importJobId, principal);
        List<String> headers = job.rows().stream().findFirst()
                .map(row -> List.copyOf(row.values().keySet())).orElse(List.of());
        StringBuilder csv = new StringBuilder(String.join(",", headers)).append(",errorCode,errorMessage\n");
        job.rows().stream().filter(row -> row.outcomeCode().equals("ERROR")).forEach(row -> {
            headers.forEach(header -> csv.append(CsvTable.escape(row.values().get(header))).append(','));
            csv.append(CsvTable.escape(row.errorCode())).append(',')
                    .append(CsvTable.escape(row.errorMessage())).append('\n');
        });
        ImportErrorFile file = new ImportErrorFile("logistics-import-errors-" + job.id() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        audit.record(principal, "IMPORT_JOB", job.id().toString(), "IMPORT_ERROR_FILE_DOWNLOADED",
                clock.instant(), "{}");
        return file;
    }

    @Transactional
    public ImportJobView importFile(String key, String productCode,
            String filename, String mediaType, byte[] bytes) {
        validateRequest(key, filename, mediaType, bytes);
        ImportMenuContext expectedContext = new ImportMenuContext(
                productCode, LogisticsImportTemplate.OBJECT_TYPE);
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        Parsed parsed = parse(bytes, expectedContext);
        String digest = digest(bytes);
        String sourceContent = encodeSource(parsed.productCode(), parsed.sourceBytes());
        if (limits.queued(parsed.rows().size())) {
            return ImportJobView.from(jobs.queue(principal.subjectId(), LogisticsImportTemplate.DOMAIN, key, digest,
                    principal.workUnitCode(), null, sourceContent, clock.instant()).stored().job());
        }
        var reservation = jobs.reserve(principal.subjectId(), LogisticsImportTemplate.DOMAIN, key, digest,
                principal.workUnitCode(), clock.instant());
        if (!reservation.owner()) return ImportJobView.from(reservation.stored().job());
        return process(reservation.stored().job(), key, parsed, digest, null, principal);
    }

    @Transactional
    public ImportJobView retry(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        var stored = jobs.findById(importJobId)
                .filter(value -> value.job().domainCode().equals(LogisticsImportTemplate.DOMAIN))
                .orElseThrow(() -> new ClientRequestException("IMPORT_JOB_NOT_FOUND", "Import job does not exist"));
        if (!stored.job().requestedBy().equals(principal.subjectId()))
            throw new ConflictException("IMPORT_RETRY_NOT_ALLOWED", "Import job belongs to a different subject");
        if ("FAILED".equals(stored.job().statusCode())) {
            String retryKey = "retry-" + UUID.randomUUID();
            return ImportJobView.from(jobs.queue(principal.subjectId(), LogisticsImportTemplate.DOMAIN, retryKey,
                    stored.job().contentSha256(), principal.workUnitCode(), stored.job().id(),
                    stored.sourceContent(), clock.instant()).stored().job());
        }
        if (stored.job().failedRows() == 0)
            throw new ConflictException("IMPORT_RETRY_NOT_AVAILABLE", "Import job has no failed rows to retry");
        Parsed parsed;
        try {
            SourceFile source = decodeSource(stored.sourceContent());
            parsed = parse(source.bytes(), source.productCode() == null ? null
                    : new ImportMenuContext(source.productCode(), LogisticsImportTemplate.OBJECT_TYPE));
        } catch (IllegalArgumentException exception) {
            throw invalidFormat();
        }
        String key = "retry-" + UUID.randomUUID();
        var reservation = jobs.reserve(principal.subjectId(), LogisticsImportTemplate.DOMAIN, key,
                stored.job().contentSha256(), principal.workUnitCode(), clock.instant());
        return process(reservation.stored().job(), key, parsed, stored.job().contentSha256(),
                stored.job().id(), principal);
    }

    @Transactional(readOnly = true)
    public ImportJobView status(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return ImportJobView.from(ownedStored(importJobId, principal).job());
    }

    @Override public String domainCode() { return LogisticsImportTemplate.DOMAIN; }

    @Override
    @Transactional
    public void processQueued(UUID jobId, SecurityPrincipal principal) {
        var stored = ownedStored(jobId, principal);
        if (!"PROCESSING".equals(stored.job().statusCode())) {
            throw new ConflictException("IMPORT_JOB_NOT_PROCESSING", "Import job is not processing");
        }
        Parsed parsed;
        try {
            SourceFile source = decodeSource(stored.sourceContent());
            parsed = parse(source.bytes(), source.productCode() == null ? null
                    : new ImportMenuContext(source.productCode(), LogisticsImportTemplate.OBJECT_TYPE));
        }
        catch (IllegalArgumentException exception) { throw invalidFormat(); }
        process(stored.job(), stored.job().idempotencyKey(), parsed, stored.job().contentSha256(),
                stored.job().retryOf(), principal);
    }

    private ImportJobView process(ImportJob reserved, String key, Parsed parsed, String digest,
            UUID retryOf, SecurityPrincipal principal) {
        List<Row> rows = parsed.rows().stream().map(row -> validate(parsed.productCode(), row)).toList();
        List<ImportRowOutcome> outcomes = new ArrayList<>();
        boolean hasErrors = rows.stream().anyMatch(row -> row.errorCode() != null);
        if (hasErrors) {
            rows.forEach(row -> outcomes.add(row.errorCode() == null
                    ? ImportRowOutcome.error(row.number(), "NOT_IMPORTED_ATOMIC_BATCH",
                            "Another row failed; the atomic batch was not written", row.values())
                    : ImportRowOutcome.error(row.number(), row.errorCode(), row.errorMessage(), row.values())));
        } else {
            rows.forEach(row -> outcomes.add(ImportRowOutcome.imported(row.number(),
                    logistics.importRow(row.draft()), row.values())));
        }
        var now = clock.instant();
        ImportJob job = jobs.complete(new ImportJob(reserved.id(), LogisticsImportTemplate.DOMAIN,
                key, digest, principal.subjectId(), principal.workUnitCode(), retryOf,
                hasErrors ? "COMPLETED_WITH_ERRORS" : "COMPLETED", reserved.createdAt(), now, outcomes,
                reserved.startedAt(), reserved.attemptCount(), null, null,
                reserved.leaseToken(), reserved.leaseUntil()),
                encodeSource(parsed.productCode(), parsed.sourceBytes()));
        audit.record(principal, "IMPORT_JOB", job.id().toString(), "IMPORT_JOB_COMPLETED", now,
                "{\"importedRows\":" + job.importedRows() + ",\"failedRows\":" + job.failedRows() + "}");
        return ImportJobView.from(job);
    }

    private Parsed parse(byte[] bytes, ImportMenuContext expectedContext) {
        try {
            BusinessImportWorkbook.Context context = BusinessImportWorkbook.context(bytes, LogisticsImportTemplate.DOMAIN);
            String productCode;
            if (expectedContext != null) {
                productCode = expectedContext.productCode();
            } else {
                if (context.productCode() == null
                        || !LogisticsImportTemplate.OBJECT_TYPE.equals(context.objectTypeCode())) throw invalidFormat();
                productCode = context.productCode();
            }
            LogisticsImportDefinition definition = definition(productCode);
            List<String> headers = LogisticsImportTemplate.headers(definition);
            List<String> codes = LogisticsImportTemplate.codes(definition);
            var sheet = BusinessImportWorkbook.read(bytes, LogisticsImportTemplate.DOMAIN,
                    headers, LogisticsImportTemplate.labels(definition), limits.maximumRows());
            if (sheet.rows().isEmpty()) throw invalidFormat();
            List<SourceRow> rows = new ArrayList<>();
            for (int index = 0; index < sheet.rows().size(); index++) {
                Map<String, String> values = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++)
                    values.put(codes.get(column), sheet.rows().get(index).get(column).trim());
                rows.add(new SourceRow(index + 3, Map.copyOf(values)));
            }
            return new Parsed(productCode, List.copyOf(rows), bytes.clone());
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidFormat();
        }
    }

    private Row validate(String productCode, SourceRow source) {
        try {
            for (String code : SYSTEM_GENERATED_CODES) {
                if (!source.values().getOrDefault(code, "").isBlank()) {
                    return new Row(source.number(), source.values(), null, "READONLY_IMPORT_FIELD",
                            "系统生成字段不得通过导入覆盖");
                }
            }
            Map<String, String> businessValues = new LinkedHashMap<>(source.values());
            SYSTEM_GENERATED_CODES.forEach(businessValues::remove);
            businessValues.remove(BusinessImportWorkbook.PHOTO_FILENAMES_CODE);
            LogisticsImportRow draft = new LogisticsImportRow(productCode, Map.copyOf(businessValues));
            logistics.validate(draft);
            return new Row(source.number(), source.values(), draft, null, null);
        } catch (ClientRequestException exception) {
            return new Row(source.number(), source.values(), null, exception.code(), exception.clientMessage());
        } catch (ConflictException exception) {
            return new Row(source.number(), source.values(), null, exception.code(), exception.getMessage());
        } catch (ResourceNotFoundException exception) {
            return new Row(source.number(), source.values(), null, exception.code(), exception.getMessage());
        }
    }

    private LogisticsImportDefinition definition(String productCode) {
        try {
            return logistics.definition(productCode);
        } catch (RuntimeException exception) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "Logistics product definition is invalid");
        }
    }

    private ImportJob ownedJob(UUID id, SecurityPrincipal principal) {
        ImportJob job = ownedStored(id, principal).job();
        return job;
    }

    private ImportJobRepository.StoredImportJob ownedStored(UUID id, SecurityPrincipal principal) {
        var stored = jobs.findById(id)
                .filter(value -> value.job().domainCode().equals(LogisticsImportTemplate.DOMAIN))
                .orElseThrow(() -> new ClientRequestException("IMPORT_JOB_NOT_FOUND", "Import job does not exist"));
        ImportJob job = stored.job();
        if (!job.requestedBy().equals(principal.subjectId()))
            throw new ConflictException("IMPORT_ERROR_FILE_NOT_ALLOWED", "Import job belongs to a different subject");
        return stored;
    }

    private void validateRequest(String key, String filename, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128 || filename == null || filename.isBlank()
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > limits.maximumBytes()) throw invalidRequest();
    }

    private static ClientRequestException invalidRequest() {
        return new ClientRequestException("INVALID_IMPORT_REQUEST", "Import request is invalid");
    }
    private static ClientRequestException invalidFormat() {
        return new ClientRequestException("INVALID_IMPORT_FORMAT", "Import workbook is invalid");
    }
    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String encodeSource(String productCode, byte[] bytes) {
        return SOURCE_PREFIX + productCode + ":" + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static SourceFile decodeSource(String sourceContent) {
        if (sourceContent != null && sourceContent.startsWith(SOURCE_PREFIX)) {
            int separator = sourceContent.indexOf(':', SOURCE_PREFIX.length());
            if (separator < 0) throw new IllegalArgumentException("INVALID_LOGISTICS_SOURCE");
            String productCode = sourceContent.substring(SOURCE_PREFIX.length(), separator);
            byte[] bytes = java.util.Base64.getDecoder().decode(sourceContent.substring(separator + 1));
            return new SourceFile(productCode, bytes);
        }
        return new SourceFile(null, java.util.Base64.getDecoder().decode(sourceContent));
    }

    private record SourceRow(int number, Map<String, String> values) {}
    private record Parsed(String productCode, List<SourceRow> rows, byte[] sourceBytes) {}
    private record SourceFile(String productCode, byte[] bytes) {}
    private record Row(int number, Map<String, String> values, LogisticsImportRow draft,
                       String errorCode, String errorMessage) {}
}
