package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OperationalReturnedCorrectionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            OperationalReturnedCorrectionService.class);
    private static final String PREFIX_SUFFIX = "-RETURNED-CORRECTION-V1:";
    private final Map<String, OperationalReturnedCorrectionDomain> domains;
    private final OperationalReturnedCorrectionBinding binding;
    private final ImportJobWriteExecutor writes;
    private final ImportJobRepository jobs;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final BusinessImportLimits limits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OperationalReturnedCorrectionService(
            List<OperationalReturnedCorrectionDomain> domains,
            OperationalReturnedCorrectionBinding binding,
            ImportJobWriteExecutor writes, ImportJobRepository jobs,
            AccessControl access, BusinessAuditRecorder audit, BusinessImportLimits limits,
            ObjectMapper objectMapper, Clock clock) {
        this.domains = domains.stream().collect(Collectors.toUnmodifiableMap(
                OperationalReturnedCorrectionDomain::domainCode, Function.identity()));
        this.binding = binding;
        this.writes = writes;
        this.jobs = jobs;
        this.access = access;
        this.audit = audit;
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WorkbookDownload download(String domainCode, String productCode) {
        OperationalReturnedCorrectionDomain domain = domain(domainCode);
        if (productCode == null || productCode.isBlank()) throw invalidContext();
        var template = domain.workbook(productCode);
        List<OperationalReturnedCorrectionDomain.ReturnedRecord> records =
                domain.returned(productCode);
        if (records.isEmpty()) {
            throw new ClientRequestException(
                    domainCode + "_RETURNED_CORRECTION_EMPTY",
                    "当前品种没有可批量修正的退回记录");
        }
        byte[] bytes = OperationalReturnedCorrectionWorkbook.create(template,
                records.stream().map(record -> new OperationalReturnedCorrectionWorkbook.Row(
                        record.id(), record.version(), record.values())).toList(), binding);
        return new WorkbookDownload(
                domain.domainLabel() + "-" + com.cofco.qiqihar.graintrade.importing.infrastructure
                        .BusinessImportWorkbook.businessLabel(productCode) + "-退回记录修正表.xlsx",
                bytes);
    }

    public ImportJobView upload(String domainCode, String key, String productCode,
            String filename, String mediaType, byte[] bytes) {
        validateUpload(key, productCode, filename, mediaType, bytes);
        OperationalReturnedCorrectionDomain domain = domain(domainCode);
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        Source source;
        try {
            var template = domain.workbook(productCode);
            List<OperationalReturnedCorrectionWorkbook.ParsedRow> parsed =
                    OperationalReturnedCorrectionWorkbook.read(bytes, template, binding);
            if (parsed.isEmpty() || parsed.size() > limits.maximumRows()) {
                throw new ClientRequestException(
                        "INVALID_IMPORT_REQUEST", "修正表没有可处理的记录");
            }
            source = new Source(productCode, parsed.stream().map(row -> new SourceRow(
                    row.worksheetRow(), row.originalRecordId(), row.originalVersion(),
                    row.values())).toList());
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_FORMAT", "退回记录修正表或填写内容无效");
        }
        String digest = digest(bytes);
        String encoded = encode(domainCode, source);
        if (limits.queued(source.rows().size())) {
            return ImportJobView.from(writes.queue(principal, domainCode, key, digest,
                    null, encoded, clock.instant()).stored().job());
        }
        var reservation = writes.reserve(principal, domainCode, key, digest, clock.instant());
        if (!reservation.owner()) return ImportJobView.from(reservation.stored().job());
        return ImportJobView.from(process(domain, reservation.stored().job(), source,
                encoded, principal));
    }

    public boolean supports(String domainCode, String sourceContent) {
        return sourceContent != null && sourceContent.startsWith(prefix(domainCode));
    }

    public void processQueued(String domainCode,
            ImportJobRepository.StoredImportJob stored, SecurityPrincipal principal) {
        OperationalReturnedCorrectionDomain domain = domain(domainCode);
        String sourceContent = stored.sourceContent();
        process(domain, stored.job(), decode(domainCode, sourceContent), sourceContent, principal);
    }

    public ImportJobView status(String domainCode, UUID jobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return ImportJobView.from(owned(domainCode, jobId, principal).job());
    }

    public ImportErrorFile errors(String domainCode, UUID jobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        ImportJob job = owned(domainCode, jobId, principal).job();
        StringBuilder csv = new StringBuilder("原单编号,工作表行号,失败原因\n");
        job.rows().stream().filter(row -> "ERROR".equals(row.outcomeCode())).forEach(row -> csv
                .append(CsvTable.escape(row.values().get("originalRecordId"))).append(',')
                .append(row.rowNumber()).append(',')
                .append(CsvTable.escape(row.errorMessage())).append('\n'));
        audit.record(principal, "IMPORT_JOB", job.id().toString(),
                "IMPORT_ERROR_FILE_DOWNLOADED", clock.instant(), "{}");
        return new ImportErrorFile("退回记录修正失败明细-" + job.id() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ImportJob process(OperationalReturnedCorrectionDomain domain, ImportJob reserved,
            Source source, String encoded, SecurityPrincipal principal) {
        List<ImportRowOutcome> outcomes = new ArrayList<>();
        for (SourceRow row : source.rows()) {
            Map<String, String> resultValues = Map.of(
                    "originalRecordId", row.originalRecordId());
            try {
                String id = domain.correctAndSubmit(source.productCode(),
                        row.originalRecordId(), row.originalVersion(), row.values());
                outcomes.add(ImportRowOutcome.imported(row.rowNumber(), id, resultValues));
            } catch (ClientRequestException exception) {
                outcomes.add(ImportRowOutcome.error(row.rowNumber(), exception.code(),
                        clientMessage(exception.code(), exception.clientMessage()), resultValues));
            } catch (ConflictException exception) {
                outcomes.add(ImportRowOutcome.error(row.rowNumber(), exception.code(),
                        clientMessage(exception.code(), exception.clientMessage()), resultValues));
            } catch (ResourceNotFoundException exception) {
                outcomes.add(ImportRowOutcome.error(row.rowNumber(), exception.code(),
                        "原记录不存在或已不可访问", resultValues));
            } catch (AccessDeniedException exception) {
                outcomes.add(ImportRowOutcome.error(row.rowNumber(), exception.code(),
                        "当前账号无权修正该记录", resultValues));
            } catch (RuntimeException exception) {
                LOGGER.warn("Returned correction row failed domain={} record={}",
                        domain.domainCode(), row.originalRecordId(), exception);
                outcomes.add(ImportRowOutcome.error(row.rowNumber(),
                        domain.domainCode() + "_RETURNED_CORRECTION_FAILED",
                        "本行未能修正，其他行不受影响", resultValues));
            }
        }
        var completedAt = clock.instant();
        String status = outcomes.stream().anyMatch(row -> "ERROR".equals(row.outcomeCode()))
                ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        ImportJob completed = new ImportJob(
                reserved.id(), domain.domainCode(), reserved.idempotencyKey(),
                reserved.contentSha256(), principal.subjectId(), principal.workUnitCode(),
                reserved.retryOf(), status, reserved.createdAt(), completedAt, outcomes,
                reserved.startedAt(), reserved.attemptCount(), null, null,
                reserved.leaseToken(), reserved.leaseUntil());
        return writes.complete(completed, encoded, principal);
    }

    private ImportJobRepository.StoredImportJob owned(
            String domainCode, UUID jobId, SecurityPrincipal principal) {
        ImportJobRepository.StoredImportJob stored = jobs.findById(jobId)
                .filter(value -> domainCode.equals(value.job().domainCode()))
                .filter(value -> supports(domainCode, value.sourceContent()))
                .orElseThrow(() -> new ClientRequestException(
                        "IMPORT_JOB_NOT_FOUND", "修正任务不存在"));
        if (!stored.job().requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_JOB_NOT_ALLOWED", "修正任务属于其他账号");
        }
        return stored;
    }

    private OperationalReturnedCorrectionDomain domain(String domainCode) {
        OperationalReturnedCorrectionDomain domain = domains.get(domainCode);
        if (domain == null) throw invalidContext();
        return domain;
    }

    private String encode(String domainCode, Source source) {
        try {
            return prefix(domainCode) + java.util.Base64.getEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(source));
        } catch (Exception exception) {
            throw new IllegalStateException("Returned correction source cannot be serialized", exception);
        }
    }

    private Source decode(String domainCode, String sourceContent) {
        if (!supports(domainCode, sourceContent)) throw invalidContext();
        try {
            return objectMapper.readValue(java.util.Base64.getDecoder().decode(
                    sourceContent.substring(prefix(domainCode).length())), Source.class);
        } catch (Exception exception) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "修正任务来源无效");
        }
    }

    private void validateUpload(String key, String productCode, String filename,
            String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128
                || productCode == null || productCode.isBlank()
                || filename == null || filename.isBlank() || filename.length() > 255
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > limits.maximumBytes()) {
            throw new ClientRequestException("INVALID_IMPORT_REQUEST", "修正表上传请求无效");
        }
    }

    private static String clientMessage(String code, String fallback) {
        return switch (code) {
            case "PRODUCTION_RECORD_VERSION_CONFLICT", "LOGISTICS_RECORD_VERSION_CONFLICT" ->
                    "记录已更新，请重新下载修正表";
            case "INVALID_PRODUCTION_TRANSITION", "INVALID_LOGISTICS_RECORD" ->
                    "原记录已不是可修正的退回状态，或填写内容不符合当前业务规则";
            default -> fallback == null || fallback.isBlank() ? "本行未能修正" : fallback;
        };
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String prefix(String domainCode) {
        return domainCode + PREFIX_SUFFIX;
    }

    private static ClientRequestException invalidContext() {
        return new ClientRequestException(
                "INVALID_IMPORT_CONTEXT", "退回记录修正的业务范围无效");
    }

    record Source(String productCode, List<SourceRow> rows) {
        Source { rows = List.copyOf(rows); }
    }

    record SourceRow(
            int rowNumber, String originalRecordId, long originalVersion,
            List<String> values) {
        SourceRow { values = List.copyOf(values); }
    }

    public record WorkbookDownload(String filename, byte[] bytes) {
        public WorkbookDownload { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
