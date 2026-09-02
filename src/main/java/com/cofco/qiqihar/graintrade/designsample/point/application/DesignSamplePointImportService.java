package com.cofco.qiqihar.graintrade.designsample.point.application;

import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleContractSnapshot;
import com.cofco.qiqihar.graintrade.designsample.metadata.application.DesignSampleMetadataService;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleFieldDefinition;
import com.cofco.qiqihar.graintrade.designsample.metadata.domain.DesignSampleContext;
import com.cofco.qiqihar.graintrade.importing.application.BusinessImportLimits;
import com.cofco.qiqihar.graintrade.importing.application.ImportErrorFile;
import com.cofco.qiqihar.graintrade.importing.application.ImportJobRepository;
import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.importing.application.SamplePointImportResult;
import com.cofco.qiqihar.graintrade.importing.infrastructure.SamplePointMasterWorkbook;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.ServiceUnavailableException;
import com.cofco.qiqihar.graintrade.shared.audit.application.BusinessAuditRecorder;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class DesignSamplePointImportService {
    private static final DesignSampleContext REFERENCE_CONTEXT =
            new DesignSampleContext("REFERENCE", "GENERAL", "REFERENCE_POINT");
    private static final List<String> LOCATION_FIELDS = List.of(
            "DSP_NAME", "DSP_REGION_CODE", "DSP_ADDRESS", "DSP_LONGITUDE", "DSP_LATITUDE");

    private final DesignSampleMetadataService metadata;
    private final AccessControl access;
    private final DesignSamplePointService points;
    private final ImportJobRepository jobs;
    private final BusinessImportLimits limits;
    private final BusinessAuditRecorder audit;
    private final Clock clock;

    public DesignSamplePointImportService(
            DesignSampleMetadataService metadata,
            AccessControl access,
            DesignSamplePointService points,
            ImportJobRepository jobs,
            BusinessImportLimits limits,
            BusinessAuditRecorder audit,
            Clock clock) {
        this.metadata = metadata;
        this.access = access;
        this.points = points;
        this.jobs = jobs;
        this.limits = limits;
        this.audit = audit;
        this.clock = clock;
    }

    public byte[] template() {
        access.require("BUSINESS_UPDATE", null);
        return SamplePointMasterWorkbook.create(templateDefinition());
    }

    public SamplePointMasterWorkbook.Template templateDefinition() {
        DesignSampleContractSnapshot contract = metadata.activeContract();
        List<SamplePointMasterWorkbook.Column> columns = LOCATION_FIELDS.stream()
                .map(contract.fieldsByCode()::get)
                .map(field -> new SamplePointMasterWorkbook.Column(
                        field.code(), publicLabel(field), true))
                .toList();
        return new SamplePointMasterWorkbook.Template(
                SamplePointMasterWorkbook.Kind.DESIGN,
                contract.contractVersion(), contract.contractDigest(), columns);
    }

    @Transactional
    public SamplePointImportResult importFile(
            String idempotencyKey, String filename, String mediaType, byte[] bytes) {
        requireUpload(idempotencyKey, filename, mediaType, bytes);
        SecurityPrincipal principal = access.require("BUSINESS_UPDATE", null);
        SamplePointMasterWorkbook.Template template = templateDefinition();
        List<SamplePointMasterWorkbook.Row> submitted;
        try {
            submitted = SamplePointMasterWorkbook.parse(bytes, template, limits.synchronousRows());
        } catch (IllegalArgumentException exception) {
            throw new ClientRequestException(
                    exception.getMessage(), "XLSX 模板或填写内容无效");
        }
        String digest = digest(bytes);
        var reservation = jobs.reserve(
                principal.subjectId(), "DESIGN_SAMPLE_POINT", idempotencyKey, digest,
                principal.workUnitCode(), clock.instant());
        if (!reservation.owner()) return result(reservation.stored().job(), true);

        DesignSampleContractSnapshot contract = metadata.activeContract();
        List<Row> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        for (SamplePointMasterWorkbook.Row submittedRow : submitted) {
            try {
                DesignSampleContext context = REFERENCE_CONTEXT;
                Map<String, JsonNode> values = new LinkedHashMap<>();
                template.columns().forEach(column -> {
                    String value = submittedRow.values().get(column.code());
                    if (value != null && !value.isBlank()) {
                        values.put(column.code(), metadataNode(value));
                    }
                });
                DesignSamplePointDraft draft = new DesignSamplePointDraft(
                        contract.contractVersion(), contract.contractDigest(), context, values);
                DesignSamplePointService.ValidatedDraft validated = points.validateForCreate(draft);
                access.require("BUSINESS_UPDATE", validated.regionCode());
                if (!names.add(validated.regionCode() + "\u0000" + validated.sampleName())
                        || !coordinates.add(validated.longitude().toPlainString() + "\u0000"
                                + validated.latitude().toPlainString())) {
                    throw new ConflictException(
                            "SAMPLE_POINT_IMPORT_DUPLICATE_ROW", "文件中存在重复名称或坐标");
                }
                rows.add(Row.valid(submittedRow, draft));
            } catch (RuntimeException exception) {
                rows.add(Row.error(submittedRow, errorCode(exception), errorMessage(exception)));
            }
        }
        return complete(reservation.stored().job(), idempotencyKey, digest, principal, rows);
    }

    @Transactional
    public ImportErrorFile errors(UUID importId) {
        SecurityPrincipal principal = access.require("BUSINESS_UPDATE", null);
        ImportJob job = jobs.findById(importId)
                .filter(stored -> "DESIGN_SAMPLE_POINT".equals(stored.job().domainCode()))
                .orElseThrow(() -> new ClientRequestException(
                        "IMPORT_JOB_NOT_FOUND", "导入记录不存在"))
                .job();
        requireOwner(job, principal);
        return errorFile(job, templateDefinition(), "design-sample-point-import-errors-");
    }

    private SamplePointImportResult complete(
            ImportJob reserved,
            String key,
            String digest,
            SecurityPrincipal principal,
            List<Row> rows) {
        boolean failed = rows.stream().anyMatch(row -> row.errorCode != null);
        List<ImportRowOutcome> outcomes = new ArrayList<>();
        if (failed) {
            rows.forEach(row -> outcomes.add(row.errorCode == null
                    ? ImportRowOutcome.error(
                            row.source.rowNumber(), "NOT_IMPORTED_ATOMIC_BATCH",
                            "其他行校验未通过，本次整批未入库", row.source.values())
                    : ImportRowOutcome.error(
                            row.source.rowNumber(), row.errorCode, row.errorMessage,
                            row.source.values())));
        } else {
            rows.forEach(row -> {
                var created = points.create(
                        "sample-import-" + reserved.id() + "-" + row.source.rowNumber(), row.draft);
                outcomes.add(ImportRowOutcome.imported(
                        row.source.rowNumber(), created.point().id().toString(), row.source.values()));
            });
        }
        Instant now = clock.instant();
        ImportJob completed = jobs.complete(new ImportJob(
                reserved.id(), "DESIGN_SAMPLE_POINT", key, digest, principal.subjectId(),
                principal.workUnitCode(), null,
                failed ? "COMPLETED_WITH_ERRORS" : "COMPLETED",
                reserved.createdAt(), now, outcomes), "sample-point-master-xlsx" + digest);
        audit.record(principal, "IMPORT_JOB", completed.id().toString(),
                "IMPORT_JOB_COMPLETED", now,
                "{\"importedRows\":" + completed.importedRows()
                        + ",\"failedRows\":" + completed.failedRows() + "}");
        return result(completed, false);
    }

    private static String publicLabel(DesignSampleFieldDefinition field) {
        if (field.code().equals("DSP_REGION_CODE")) return "所属地区";
        return field.unit() == null ? field.label() : field.label() + "（" + field.unit() + "）";
    }

    private static JsonNode metadataNode(String value) {
        return tools.jackson.databind.node.JsonNodeFactory.instance.textNode(value.trim());
    }

    private void requireUpload(String key, String filename, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128
                || filename == null || filename.isBlank() || filename.length() > 255
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > limits.maximumBytes()) {
            throw new ClientRequestException("INVALID_IMPORT_REQUEST", "导入文件请求无效");
        }
    }

    private static void requireOwner(ImportJob job, SecurityPrincipal principal) {
        if (!job.requestedBy().equals(principal.subjectId())) {
            throw new ConflictException(
                    "IMPORT_ERROR_FILE_NOT_ALLOWED", "导入记录属于其他用户");
        }
    }

    private static ImportErrorFile errorFile(
            ImportJob job, SamplePointMasterWorkbook.Template template, String prefix) {
        StringBuilder csv = new StringBuilder();
        template.columns().forEach(column -> csv.append(CsvTable.escape(column.label())).append(','));
        csv.append("错误代码,错误说明\n");
        job.rows().stream().filter(row -> "ERROR".equals(row.outcomeCode())).forEach(row -> {
            template.columns().forEach(column -> csv.append(
                    CsvTable.escape(row.values().get(column.code()))).append(','));
            csv.append(CsvTable.escape(row.errorCode())).append(',')
                    .append(CsvTable.escape(row.errorMessage())).append('\n');
        });
        return new ImportErrorFile(prefix + job.id() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static SamplePointImportResult result(ImportJob job, boolean replayed) {
        return new SamplePointImportResult(
                job.id(), job.statusCode(), job.importedRows(), job.failedRows(),
                job.completedAt(), replayed);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest sample point workbook", exception);
        }
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof ClientRequestException value) return value.code();
        if (exception instanceof ConflictException value) return value.code();
        if (exception instanceof AccessDeniedException value) return value.code();
        if (exception instanceof ServiceUnavailableException value) return value.code();
        throw exception;
    }

    private static String errorMessage(RuntimeException exception) {
        if (exception instanceof ClientRequestException value) return value.clientMessage();
        if (exception instanceof ConflictException value) return value.clientMessage();
        if (exception instanceof ServiceUnavailableException value) return value.clientMessage();
        if (exception instanceof AccessDeniedException) return "当前账号无权维护该地区";
        throw exception;
    }

    private record Row(
            SamplePointMasterWorkbook.Row source,
            DesignSamplePointDraft draft,
            String errorCode,
            String errorMessage) {
        static Row valid(SamplePointMasterWorkbook.Row source, DesignSamplePointDraft draft) {
            return new Row(source, draft, null, null);
        }

        static Row error(
                SamplePointMasterWorkbook.Row source, String errorCode, String errorMessage) {
            return new Row(source, null, errorCode, errorMessage);
        }
    }
}
