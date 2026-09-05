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
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
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
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class DesignSamplePointImportService {
    private static final List<String> CONTEXT_FIELDS = List.of(
            "DOMAIN_CODE", "PRODUCT_CODE", "OBJECT_TYPE_CODE");
    private static final List<String> PLANNING_FIELDS = List.of(
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

    public byte[] template(String domainCode) {
        access.require("BUSINESS_UPDATE", null);
        return SamplePointMasterWorkbook.create(templateDefinition(domainCode));
    }

    public SamplePointMasterWorkbook.Template templateDefinition() {
        return templateDefinition(null);
    }

    public SamplePointMasterWorkbook.Template templateDefinition(String requestedDomain) {
        DesignSampleContractSnapshot contract = metadata.activeContract();
        if (requestedDomain != null) resolveDomain(contract, requestedDomain);
        List<SamplePointMasterWorkbook.Column> contextColumns = CONTEXT_FIELDS.stream()
                .map(contract.fieldsByCode()::get)
                .map(field -> new SamplePointMasterWorkbook.Column(
                        field.code(), publicLabel(field), true,
                        contextOptions(contract, field.code())))
                .toList();
        List<SamplePointMasterWorkbook.Column> planningColumns = PLANNING_FIELDS.stream()
                .map(contract.fieldsByCode()::get)
                .map(field -> new SamplePointMasterWorkbook.Column(
                        field.code(), publicLabel(field), true))
                .toList();
        List<SamplePointMasterWorkbook.Column> columns = new ArrayList<>(contextColumns);
        columns.addAll(planningColumns);
        return new SamplePointMasterWorkbook.Template(
                SamplePointMasterWorkbook.Kind.DESIGN,
                contract.contractVersion(), contract.contractDigest(), columns,
                "设计样本点");
    }

    @Transactional
    public SamplePointImportResult importFile(
            String requestedDomain, String idempotencyKey, String filename, String mediaType, byte[] bytes) {
        requireUpload(idempotencyKey, filename, mediaType, bytes);
        SecurityPrincipal principal = access.require("BUSINESS_UPDATE", null);
        DesignSampleContractSnapshot contract = metadata.activeContract();
        SamplePointMasterWorkbook.Template template = templateDefinition(requestedDomain);
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

        List<Row> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        for (SamplePointMasterWorkbook.Row submittedRow : submitted) {
            try {
                String rowDomain = resolveDomain(contract, submittedRow.values().get("DOMAIN_CODE"));
                String product = resolveProduct(contract, submittedRow.values().get("PRODUCT_CODE"));
                String objectType = resolveObjectType(
                        contract, rowDomain, submittedRow.values().get("OBJECT_TYPE_CODE"));
                DesignSampleContext context = new DesignSampleContext(rowDomain, product, objectType);
                Map<String, JsonNode> values = new LinkedHashMap<>();
                template.columns().stream().filter(column -> PLANNING_FIELDS.contains(column.code())).forEach(column -> {
                    String value = submittedRow.values().get(column.code());
                    if (value != null && !value.isBlank()) {
                        values.put(column.code(), metadataNode(value));
                    }
                });
                values.put("DSP_MAINTAINER_NAME", metadataNode(principal.displayName()));
                values.put("DSP_MAINTAINER_UNIT", metadataNode(principal.workUnitName()));
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
                rows.add(Row.error(submittedRow, errorCode(exception),
                        locatedError(template, submittedRow, exception)));
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

    private static String resolveDomain(DesignSampleContractSnapshot contract, String value) {
        String token = token(value);
        if (Set.of("产情", "产情类", "产情域").contains(token)) return "PRODUCTION";
        if (Set.of("市场", "市场类", "市场域").contains(token)) return "MARKET";
        return contract.domains().stream()
                .filter(item -> item.code().equals("PRODUCTION") || item.code().equals("MARKET"))
                .filter(item -> matches(token, item.code(), item.label(), item.aliases()))
                .map(DesignSampleContractSnapshot.DomainDefinition::code)
                .findFirst().orElseThrow(() -> new ClientRequestException(
                        "INVALID_DESIGN_SAMPLE_DOMAIN", "业务分类应填写产情或市场"));
    }

    private static String resolveProduct(DesignSampleContractSnapshot contract, String value) {
        String token = token(value);
        return contract.products().stream()
                .filter(item -> !item.code().equals("GENERAL"))
                .filter(item -> matches(token, item.code(), item.label(), item.aliases()))
                .map(DesignSampleContractSnapshot.ProductDefinition::code)
                .findFirst().orElseThrow(() -> new ClientRequestException(
                        "INVALID_DESIGN_SAMPLE_PRODUCT", "品种名称或代码不在合同中"));
    }

    private static String resolveObjectType(
            DesignSampleContractSnapshot contract, String domain, String value) {
        String token = token(value);
        return contract.objectTypes().stream().filter(item -> item.domainCode().equals(domain))
                .filter(item -> matches(token, item.code(), item.label(), item.aliases()))
                .map(DesignSampleContractSnapshot.ObjectTypeDefinition::code)
                .findFirst().orElseThrow(() -> new ClientRequestException(
                        "INVALID_DESIGN_SAMPLE_OBJECT_TYPE", "参考对象类型不适用于当前业务分类"));
    }

    private static boolean matches(String token, String code, String label, List<String> aliases) {
        return token.equals(token(code)) || token.equals(token(label))
                || aliases.stream().anyMatch(alias -> token.equals(token(alias)));
    }

    private static String token(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\u3000]+", "").toUpperCase(Locale.ROOT);
    }

    private static String locatedError(
            SamplePointMasterWorkbook.Template template,
            SamplePointMasterWorkbook.Row row,
            RuntimeException exception) {
        String column = firstInvalidColumn(row);
        if (column == null) column = switch (errorCode(exception)) {
            case "INVALID_DESIGN_SAMPLE_DOMAIN", "DESIGN_SAMPLE_DOMAIN_MISMATCH" -> "业务分类";
            case "INVALID_DESIGN_SAMPLE_PRODUCT" -> "品种";
            case "INVALID_DESIGN_SAMPLE_OBJECT_TYPE", "INVALID_DESIGN_SAMPLE_CONTEXT" -> "参考对象类型";
            case "COORDINATE_OUTSIDE_REGION", "ADMIN_BOUNDARY_UNAVAILABLE" -> "经纬度";
            default -> "点位名称";
        };
        return "工作表“" + template.sheetLabel() + "”第" + row.rowNumber()
                + "行“" + column + "”列：" + errorMessage(exception);
    }

    private static String firstInvalidColumn(SamplePointMasterWorkbook.Row row) {
        for (var field : List.of(
                Map.entry("DOMAIN_CODE", "业务分类"),
                Map.entry("PRODUCT_CODE", "品种"),
                Map.entry("OBJECT_TYPE_CODE", "参考对象类型"),
                Map.entry("DSP_NAME", "点位名称"),
                Map.entry("DSP_REGION_CODE", "所属地区"),
                Map.entry("DSP_ADDRESS", "详细地址"),
                Map.entry("DSP_LONGITUDE", "经度"),
                Map.entry("DSP_LATITUDE", "纬度"))) {
            if (row.values().getOrDefault(field.getKey(), "").isBlank()) return field.getValue();
        }
        try {
            new java.math.BigDecimal(row.values().get("DSP_LONGITUDE"));
        } catch (RuntimeException invalid) {
            return "经度";
        }
        try {
            new java.math.BigDecimal(row.values().get("DSP_LATITUDE"));
        } catch (RuntimeException invalid) {
            return "纬度";
        }
        return null;
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
        if (field.code().equals("DOMAIN_CODE")) return "业务分类";
        if (field.code().equals("PRODUCT_CODE")) return "品种";
        if (field.code().equals("OBJECT_TYPE_CODE")) return "参考对象类型";
        if (field.code().equals("DSP_REGION_CODE")) return "所属地区";
        return field.unit() == null ? field.label() : field.label() + "（" + field.unit() + "）";
    }

    private static List<String> contextOptions(
            DesignSampleContractSnapshot contract, String fieldCode) {
        if (fieldCode.equals("DOMAIN_CODE")) {
            return contract.domains().stream()
                    .filter(item -> Set.of("PRODUCTION", "MARKET").contains(item.code()))
                    .filter(item -> contract.supportedContexts().stream()
                            .anyMatch(context -> context.domainCode().equals(item.code())))
                    .map(item -> item.code().equals("PRODUCTION") ? "产情" : "市场")
                    .toList();
        }
        Set<String> supportedCodes = contract.supportedContexts().stream()
                .filter(context -> Set.of("PRODUCTION", "MARKET").contains(context.domainCode()))
                .map(context -> fieldCode.equals("PRODUCT_CODE")
                        ? context.productCode() : context.objectTypeCode())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (fieldCode.equals("PRODUCT_CODE")) {
            return contract.products().stream()
                    .filter(item -> supportedCodes.contains(item.code()))
                    .map(item -> BusinessImportWorkbook.businessLabel(item.code()))
                    .toList();
        }
        return contract.objectTypes().stream()
                .filter(item -> Set.of("PRODUCTION", "MARKET").contains(item.domainCode()))
                .filter(item -> supportedCodes.contains(item.code()))
                .map(item -> BusinessImportWorkbook.businessLabel(item.code()))
                .toList();
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
