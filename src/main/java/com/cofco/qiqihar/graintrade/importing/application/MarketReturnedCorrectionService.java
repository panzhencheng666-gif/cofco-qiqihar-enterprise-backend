package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.domain.CsvTable;
import com.cofco.qiqihar.graintrade.importing.domain.ImportJob;
import com.cofco.qiqihar.graintrade.importing.domain.ImportRowOutcome;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportDefinition;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketImportRow;
import com.cofco.qiqihar.graintrade.market.importing.MarketReturnedCorrectionPort;
import com.cofco.qiqihar.graintrade.market.importing.MarketReturnedCorrectionRecord;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MarketReturnedCorrectionService {
    public static final String SOURCE_PREFIX = "MARKET-RETURNED-CORRECTION-V1:";
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketReturnedCorrectionService.class);
    private final MarketReturnedCorrectionPort returnedRecords;
    private final MarketImportPort market;
    private final BusinessImportTemplateCatalog templates;
    private final RegionImportResolver regions;
    private final MarketReturnedCorrectionRowService rowService;
    private final MarketReturnedCorrectionBinding binding;
    private final ImportJobWriteExecutor jobWrites;
    private final ImportJobRepository jobs;
    private final AccessControl access;
    private final BusinessAuditRecorder audit;
    private final BusinessImportLimits limits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MarketReturnedCorrectionService(
            MarketReturnedCorrectionPort returnedRecords, MarketImportPort market,
            BusinessImportTemplateCatalog templates, RegionImportResolver regions,
            MarketReturnedCorrectionRowService rowService,
            MarketReturnedCorrectionBinding binding, ImportJobWriteExecutor jobWrites,
            ImportJobRepository jobs, AccessControl access, BusinessAuditRecorder audit,
            BusinessImportLimits limits, ObjectMapper objectMapper, Clock clock) {
        this.returnedRecords = returnedRecords;
        this.market = market;
        this.templates = templates;
        this.regions = regions;
        this.rowService = rowService;
        this.binding = binding;
        this.jobWrites = jobWrites;
        this.jobs = jobs;
        this.access = access;
        this.audit = audit;
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WorkbookDownload download(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            throw new ClientRequestException("INVALID_IMPORT_CONTEXT", "修正表的产品无效");
        }
        List<BusinessImportTemplateCatalog.ObjectTypeOption> objectTypes =
                templates.objectTypes(MarketImportTemplate.DOMAIN, productCode);
        List<MarketImportDefinition> definitions = objectTypes.stream()
                .map(option -> market.definition(productCode, option.code())).toList();
        BusinessImportWorkbook.Template ordinaryTemplate =
                MarketImportTemplate.productWorkbook(productCode, definitions, objectTypes);
        List<MarketReturnedCorrectionRecord> records = returnedRecords.returned(productCode);
        if (records.isEmpty()) {
            throw new ClientRequestException(
                    "MARKET_RETURNED_CORRECTION_EMPTY",
                    "当前品种没有可批量修正的地区与经纬度不匹配退回记录");
        }

        Map<String, String> objectTypeLabels = objectTypes.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        BusinessImportTemplateCatalog.ObjectTypeOption::code,
                        BusinessImportTemplateCatalog.ObjectTypeOption::label));
        Map<String, Map<String, String>> optionLabels = optionLabels(definitions);
        List<MarketReturnedCorrectionWorkbook.Row> rows = records.stream()
                .map(record -> new MarketReturnedCorrectionWorkbook.Row(
                        record.id(), record.version(), values(
                                ordinaryTemplate, record, objectTypeLabels, optionLabels)))
                .toList();
        return new WorkbookDownload(
                "市场-" + BusinessImportWorkbook.businessLabel(productCode) + "-退回记录修正表.xlsx",
                MarketReturnedCorrectionWorkbook.create(ordinaryTemplate, rows, binding));
    }

    public ImportJobView upload(String key, String productCode, String filename,
            String mediaType, byte[] workbookBytes) {
        validateUpload(key, filename, mediaType, workbookBytes);
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        CorrectionSource source;
        try {
            source = source(productCode, workbookBytes);
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Returned correction workbook was rejected", exception);
            throw new ClientRequestException(
                    "INVALID_IMPORT_FORMAT", "退回记录修正表或填写内容无效");
        }
        String digest = digest(workbookBytes);
        String encoded = encode(source);
        if (limits.queued(source.rows().size())) {
            var reservation = jobWrites.queue(
                    principal, MarketImportTemplate.DOMAIN, key, digest, null, encoded, clock.instant());
            return ImportJobView.from(reservation.stored().job());
        }
        var reservation = jobWrites.reserve(
                principal, MarketImportTemplate.DOMAIN, key, digest, clock.instant());
        if (!reservation.owner()) return ImportJobView.from(reservation.stored().job());
        return ImportJobView.from(process(reservation.stored().job(), source, digest, principal));
    }

    public boolean supports(String sourceContent) {
        return sourceContent != null && sourceContent.startsWith(SOURCE_PREFIX);
    }

    public void processQueued(ImportJobRepository.StoredImportJob stored, SecurityPrincipal principal) {
        process(stored.job(), decode(stored.sourceContent()), stored.job().contentSha256(), principal);
    }

    public ImportJobView status(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        return ImportJobView.from(owned(importJobId, principal).job());
    }

    public ImportErrorFile errors(UUID importJobId) {
        SecurityPrincipal principal = access.require("BUSINESS_IMPORT", null);
        ImportJob job = owned(importJobId, principal).job();
        StringBuilder csv = new StringBuilder("原单编号,工作表行号,失败原因\n");
        job.rows().stream().filter(row -> "ERROR".equals(row.outcomeCode())).forEach(row -> csv
                .append(CsvTable.escape(row.values().get("originalRecordId"))).append(',')
                .append(row.rowNumber()).append(',')
                .append(CsvTable.escape(row.errorMessage())).append('\n'));
        audit.record(principal, "IMPORT_JOB", job.id().toString(),
                "IMPORT_ERROR_FILE_DOWNLOADED", clock.instant(), "{}");
        return new ImportErrorFile("市场退回记录修正失败明细-" + job.id() + ".csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private CorrectionSource source(String productCode, byte[] workbookBytes) {
        if (productCode == null || productCode.isBlank()) {
            throw new ClientRequestException("INVALID_IMPORT_CONTEXT", "修正表的产品无效");
        }
        List<BusinessImportTemplateCatalog.ObjectTypeOption> objectTypes =
                templates.objectTypes(MarketImportTemplate.DOMAIN, productCode);
        List<MarketImportDefinition> definitions = objectTypes.stream()
                .map(option -> market.definition(productCode, option.code())).toList();
        BusinessImportWorkbook.Template ordinaryTemplate =
                MarketImportTemplate.productWorkbook(productCode, definitions, objectTypes);
        List<MarketReturnedCorrectionWorkbook.ParsedRow> parsed =
                MarketReturnedCorrectionWorkbook.read(workbookBytes, ordinaryTemplate, binding);
        if (parsed.isEmpty() || parsed.size() > limits.maximumRows()) {
            throw new ClientRequestException("INVALID_IMPORT_REQUEST", "修正表没有可处理的记录");
        }
        Map<String, String> objectTypeByLabel = objectTypes.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        BusinessImportTemplateCatalog.ObjectTypeOption::label,
                        BusinessImportTemplateCatalog.ObjectTypeOption::code));
        Map<String, MarketImportDefinition> definitionByObject = definitions.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        MarketImportDefinition::objectTypeCode, definition -> definition));
        List<String> codes = MarketReturnedCorrectionWorkbook.businessHeaders(ordinaryTemplate);
        List<CorrectionRow> rows = parsed.stream()
                .map(row -> correctionRow(
                        productCode, row, codes, objectTypeByLabel, definitionByObject))
                .toList();
        return new CorrectionSource(MarketReturnedCorrectionWorkbook.PURPOSE, productCode, rows);
    }

    private CorrectionRow correctionRow(String productCode,
            MarketReturnedCorrectionWorkbook.ParsedRow parsed, List<String> codes,
            Map<String, String> objectTypeByLabel,
            Map<String, MarketImportDefinition> definitionByObject) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("originalRecordId", parsed.originalRecordId());
        for (int index = 0; index < codes.size(); index++) {
            values.put(codes.get(index), parsed.values().get(index).trim());
        }
        try {
            String suppliedObject = values.getOrDefault("objectTypeCode", "");
            String objectType = objectTypeByLabel.getOrDefault(suppliedObject, suppliedObject);
            MarketImportDefinition definition = definitionByObject.get(objectType);
            if (definition == null) {
                throw new ClientRequestException(
                        "IMPORT_OBJECT_TYPE_INVALID", "样本点类型不在当前产品的填报范围内");
            }
            MarketImportRow row = toMarketRow(productCode, definition, values);
            Map<String, String> normalized = new LinkedHashMap<>(values);
            normalized.put("objectTypeCode", objectType);
            normalized.put("MKT_REGION", row.coreValues().get("MKT_REGION"));
            return CorrectionRow.valid(parsed.worksheetRow(), parsed.originalRecordId(),
                    parsed.originalVersion(), normalized, row);
        } catch (ClientRequestException exception) {
            return CorrectionRow.error(parsed.worksheetRow(), parsed.originalRecordId(),
                    parsed.originalVersion(), values, exception.code(), exception.clientMessage());
        } catch (RuntimeException exception) {
            return CorrectionRow.error(parsed.worksheetRow(), parsed.originalRecordId(),
                    parsed.originalVersion(), values,
                    "IMPORT_ROW_VALUE_FORMAT", "本行填写格式或受控选项不正确");
        }
    }

    private MarketImportRow toMarketRow(String productCode,
            MarketImportDefinition definition, Map<String, String> values) {
        Map<String, MarketImportDefinition.Field> coreFields = definition.coreFields().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        MarketImportDefinition.Field::code, field -> field));
        Map<String, MarketImportDefinition.Field> factFields = definition.factFields().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        MarketImportDefinition.Field::code, field -> field));
        Map<String, String> core = new LinkedHashMap<>();
        core.put("MKT_OBJECT_TYPE", definition.objectTypeCode());
        for (MarketImportDefinition.Field field : coreFields.values()) {
            if (field.readOnly() || "MKT_OBJECT_TYPE".equals(field.code())
                    || "MKT_REPORTER_NAME".equals(field.code())
                    || "MKT_TRADE_DATE".equals(field.code())) continue;
            String value = values.get(field.code());
            if (value == null || value.isBlank()) continue;
            if ("MKT_REGION".equals(field.code())) value = regions.resolve(value);
            else value = optionValue(field, value);
            core.put(field.code(), value);
        }
        int year = Integer.parseInt(values.getOrDefault("surveyYear", ""));
        String monthValue = values.getOrDefault("surveyMonth", "");
        int month = monthValue.isBlank() ? 1 : Integer.parseInt(monthValue);
        if (year < 1900 || year > 2200 || month < 1 || month > 12) {
            throw new ClientRequestException(
                    "IMPORT_ROW_VALUE_FORMAT", "数据年份或月份填写不正确");
        }
        core.put("MKT_TRADE_DATE", LocalDate.of(year, month, 1).toString());

        Map<String, BigDecimal> facts = new LinkedHashMap<>();
        for (MarketImportDefinition.Field field : factFields.values()) {
            String value = values.get(field.code());
            if (value == null || value.isBlank()) continue;
            facts.put(field.code(), PlainDecimal.parse(value,
                    field.precision() - field.scale(), field.scale(), "IMPORT_ROW_VALUE_FORMAT"));
        }
        return new MarketImportRow(productCode, core, facts, List.of());
    }

    private static String optionValue(MarketImportDefinition.Field field, String supplied) {
        if (field.options().isEmpty()) return supplied;
        return field.options().stream()
                .filter(option -> option.label().equals(supplied) || option.value().equals(supplied))
                .map(MarketImportDefinition.Option::value).findFirst()
                .orElseThrow(() -> new ClientRequestException(
                        "IMPORT_VALUE_FORMAT_INVALID", "受控选项填写不正确"));
    }

    private ImportJob process(ImportJob reserved, CorrectionSource source,
            String digest, SecurityPrincipal principal) {
        List<ImportRowOutcome> outcomes = new ArrayList<>(source.rows().size());
        for (CorrectionRow row : source.rows()) outcomes.add(processRow(row));
        var completedAt = clock.instant();
        String status = outcomes.stream().anyMatch(row -> "ERROR".equals(row.outcomeCode()))
                ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        ImportJob completed = new ImportJob(reserved.id(), MarketImportTemplate.DOMAIN,
                reserved.idempotencyKey(), digest, principal.subjectId(), principal.workUnitCode(),
                reserved.retryOf(), status, reserved.createdAt(), completedAt, outcomes,
                reserved.startedAt(), reserved.attemptCount(), null, null,
                reserved.leaseToken(), reserved.leaseUntil());
        return jobWrites.complete(completed, encode(source), principal);
    }

    private ImportRowOutcome processRow(CorrectionRow row) {
        if (row.errorCode() != null) {
            return ImportRowOutcome.error(
                    row.rowNumber(), row.errorCode(), row.errorMessage(), row.values());
        }
        try {
            String id = rowService.correctAndSubmit(
                    row.originalRecordId(), row.originalVersion(), row.marketRow());
            return ImportRowOutcome.imported(row.rowNumber(), id, row.values());
        } catch (ClientRequestException exception) {
            return ImportRowOutcome.error(row.rowNumber(), exception.code(),
                    clientMessage(exception.code(), exception.clientMessage()), row.values());
        } catch (ConflictException exception) {
            return ImportRowOutcome.error(row.rowNumber(), exception.code(),
                    clientMessage(exception.code(), exception.clientMessage()), row.values());
        } catch (ResourceNotFoundException exception) {
            return ImportRowOutcome.error(row.rowNumber(), exception.code(),
                    "原记录不存在或已不可访问", row.values());
        } catch (AccessDeniedException exception) {
            return ImportRowOutcome.error(row.rowNumber(), exception.code(),
                    "当前账号无权修正该记录", row.values());
        } catch (RuntimeException exception) {
            LOGGER.warn("Returned market correction row failed rowNumber={} originalId={}",
                    row.rowNumber(), row.originalRecordId(), exception);
            return ImportRowOutcome.error(row.rowNumber(), "MARKET_RETURNED_CORRECTION_FAILED",
                    "本行未能修正，其他行不受影响", row.values());
        }
    }

    private static String clientMessage(String code, String fallback) {
        return switch (code) {
            case "MARKET_RETURNED_CORRECTION_STATE_CONFLICT" -> "原记录已不是可修正的退回状态";
            case "MARKET_RECORD_VERSION_CONFLICT" -> "记录已更新，请重新下载修正表";
            case "MARKET_SAMPLE_POINT_OUTSIDE_REGION" ->
                    "样本点经纬度不在所选地区范围内，请核对后重新上传";
            case "INVALID_MARKET_RECORD" -> "本行市场业务数据填写不正确";
            default -> fallback == null || fallback.isBlank() ? "本行未能修正" : fallback;
        };
    }

    private ImportJobRepository.StoredImportJob owned(
            UUID jobId, SecurityPrincipal principal) {
        ImportJobRepository.StoredImportJob stored = jobs.findById(jobId)
                .filter(value -> MarketImportTemplate.DOMAIN.equals(value.job().domainCode()))
                .filter(value -> supports(value.sourceContent()))
                .orElseThrow(() -> new ClientRequestException(
                        "IMPORT_JOB_NOT_FOUND", "修正任务不存在"));
        if (!stored.job().requestedBy().equals(principal.subjectId())) {
            throw new ConflictException("IMPORT_JOB_NOT_ALLOWED", "修正任务属于其他账号");
        }
        return stored;
    }

    private String encode(CorrectionSource source) {
        try {
            return SOURCE_PREFIX + java.util.Base64.getEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(source));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Returned correction source cannot be serialized", exception);
        }
    }

    private CorrectionSource decode(String sourceContent) {
        if (!supports(sourceContent)) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "修正任务来源无效");
        }
        try {
            CorrectionSource source = objectMapper.readValue(
                    java.util.Base64.getDecoder().decode(
                            sourceContent.substring(SOURCE_PREFIX.length())), CorrectionSource.class);
            if (!MarketReturnedCorrectionWorkbook.PURPOSE.equals(source.purpose())) {
                throw new IllegalArgumentException("purpose");
            }
            return source;
        } catch (Exception exception) {
            throw new ClientRequestException("INVALID_IMPORT_TEMPLATE", "修正任务来源无效");
        }
    }

    private void validateUpload(String key, String filename, String mediaType, byte[] bytes) {
        if (key == null || key.isBlank() || key.length() > 128
                || filename == null || filename.isBlank() || filename.length() > 255
                || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (mediaType != null && !mediaType.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                || bytes == null || bytes.length == 0 || bytes.length > limits.maximumBytes()) {
            throw new ClientRequestException("INVALID_IMPORT_REQUEST", "修正表上传请求无效");
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> values(
            BusinessImportWorkbook.Template ordinaryTemplate,
            MarketReturnedCorrectionRecord record,
            Map<String, String> objectTypeLabels,
            Map<String, Map<String, String>> optionLabels) {
        return MarketReturnedCorrectionWorkbook.businessHeaders(ordinaryTemplate).stream()
                .map(code -> normalizedWorkbookValue(
                        value(code, record, objectTypeLabels, optionLabels),
                        ordinaryTemplate.rules().get(ordinaryTemplate.headers().indexOf(code))))
                .toList();
    }

    private static String normalizedWorkbookValue(
            String value, BusinessImportWorkbook.ColumnRule rule) {
        if (value == null || value.isBlank() || !"DECIMAL".equals(rule.valueType())) return value;
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    private String value(
            String code, MarketReturnedCorrectionRecord record,
            Map<String, String> objectTypeLabels,
            Map<String, Map<String, String>> optionLabels) {
        if ("objectTypeCode".equals(code)) {
            return objectTypeLabels.getOrDefault(record.objectTypeCode(), record.objectTypeCode());
        }
        if ("surveyYear".equals(code)) return Integer.toString(record.surveyYear());
        if ("surveyMonth".equals(code)) {
            return record.surveyMonth() == null ? "" : Integer.toString(record.surveyMonth());
        }
        if ("MKT_REGION".equals(code)) return regions.displayPath(record.regionCode());
        String internalValue = record.coreValues().containsKey(code)
                ? record.coreValues().get(code) : record.facts().get(code);
        if (internalValue == null) return "";
        return optionLabels.getOrDefault(code, Map.of()).getOrDefault(internalValue, internalValue);
    }

    private static Map<String, Map<String, String>> optionLabels(
            List<MarketImportDefinition> definitions) {
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        definitions.stream().flatMap(definition -> definition.coreFields().stream())
                .filter(field -> !field.options().isEmpty())
                .forEach(field -> {
                    Map<String, String> byValue = labels.computeIfAbsent(
                            field.code(), ignored -> new LinkedHashMap<>());
                    field.options().forEach(option -> byValue.putIfAbsent(option.value(), option.label()));
                });
        return labels;
    }

    public record CorrectionSource(
            String purpose, String productCode, List<CorrectionRow> rows) {
        public CorrectionSource { rows = List.copyOf(rows); }
    }

    public record CorrectionRow(int rowNumber, String originalRecordId, long originalVersion,
            Map<String, String> values, MarketImportRow marketRow,
            String errorCode, String errorMessage) {
        public CorrectionRow { values = Map.copyOf(values); }

        static CorrectionRow valid(int rowNumber, String originalId, long originalVersion,
                Map<String, String> values, MarketImportRow marketRow) {
            return new CorrectionRow(rowNumber, originalId, originalVersion,
                    values, marketRow, null, null);
        }

        static CorrectionRow error(int rowNumber, String originalId, long originalVersion,
                Map<String, String> values, String errorCode, String errorMessage) {
            return new CorrectionRow(rowNumber, originalId, originalVersion,
                    values, null, errorCode, errorMessage);
        }
    }

    public record WorkbookDownload(String filename, byte[] bytes) {
        public WorkbookDownload {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
