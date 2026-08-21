package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDraft;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRecordView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsService;
import com.cofco.qiqihar.graintrade.logistics.domain.LogisticsStatus;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportDefinition;
import com.cofco.qiqihar.graintrade.logistics.importing.LogisticsImportPort;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LogisticsReturnedCorrectionDomain
        implements OperationalReturnedCorrectionDomain {
    private static final int PAGE_SIZE = 20;
    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "fillingDate", "LOG_REPORTER", "LOG_STATUS");
    private final LogisticsService logistics;
    private final LogisticsImportPort imports;
    private final RegionImportResolver regions;

    public LogisticsReturnedCorrectionDomain(
            LogisticsService logistics, LogisticsImportPort imports,
            RegionImportResolver regions) {
        this.logistics = logistics;
        this.imports = imports;
        this.regions = regions;
    }

    @Override public String domainCode() { return LogisticsImportTemplate.DOMAIN; }
    @Override public String domainLabel() { return "物流"; }

    @Override
    public BusinessImportWorkbook.Template workbook(String productCode) {
        return LogisticsImportTemplate.workbook(productCode, imports.definition(productCode));
    }

    @Override
    public List<ReturnedRecord> returned(String productCode) {
        LogisticsImportDefinition definition = imports.definition(productCode);
        List<String> codes = businessCodes(definition);
        ArrayList<ReturnedRecord> result = new ArrayList<>();
        for (int page = 0;; page++) {
            var records = logistics.list(productCode, page, PAGE_SIZE,
                    Map.of("status", LogisticsStatus.RETURNED.name()));
            for (LogisticsRecordView listed : records.items()) {
                LogisticsRecordView view = logistics.detail(listed.id());
                if (view.status() == LogisticsStatus.RETURNED
                        && view.allowedActions().containsAll(List.of("SAVE", "SUBMIT"))) {
                    result.add(new ReturnedRecord(view.id(), view.version(),
                            codes.stream().map(code -> value(code, view)).toList()));
                }
            }
            if (page + 1 >= records.totalPages()) break;
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String correctAndSubmit(String productCode, String originalId,
            long originalVersion, List<String> suppliedValues) {
        LogisticsRecordView original = logistics.detail(originalId);
        if (!productCode.equals(original.productCode())) {
            throw new ClientRequestException(
                    "LOGISTICS_RETURNED_CORRECTION_PRODUCT_MISMATCH",
                    "原记录与修正表品种不一致");
        }
        if (original.status() != LogisticsStatus.RETURNED) {
            throw new ConflictException(
                    "LOGISTICS_RETURNED_CORRECTION_STATE_CONFLICT",
                    "原记录已不是可修正的退回状态");
        }
        if (!original.allowedActions().containsAll(List.of("SAVE", "SUBMIT"))) {
            throw new AccessDeniedException(
                    "LOGISTICS_RETURNED_CORRECTION_NOT_ALLOWED", "当前账号无权修正该记录");
        }
        LogisticsImportDefinition definition = imports.definition(productCode);
        List<String> codes = businessCodes(definition);
        if (codes.size() != suppliedValues.size()) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_TEMPLATE", "修正表字段与当前物流表单不一致");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < codes.size(); index++) {
            values.put(codes.get(index), suppliedValues.get(index).trim());
        }
        Map<String, LogisticsImportDefinition.Field> fields = definition.fields().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        LogisticsImportDefinition.Field::code, field -> field));
        Map<String, String> business = new LinkedHashMap<>();
        values.forEach((code, supplied) -> {
            if (SYSTEM_FIELDS.contains(code) || supplied.isBlank()) return;
            String value = supplied;
            LogisticsImportDefinition.Field field = fields.get(code);
            if (field != null && !field.options().isEmpty()) {
                value = field.options().stream()
                        .filter(option -> option.label().equals(supplied)
                                || option.value().equals(supplied))
                        .map(LogisticsImportDefinition.Option::value)
                        .findFirst().orElse(supplied);
            }
            if ("LOG_REGION".equals(code)) value = regions.resolve(value);
            business.put(code, value);
        });
        LogisticsRecordView saved = logistics.save(originalId, originalVersion,
                new LogisticsDraft(productCode, business));
        logistics.submit(originalId, saved.version());
        return originalId;
    }

    private String value(String code, LogisticsRecordView view) {
        if ("LOG_REGION".equals(code)) {
            return regions.displayPath(view.values().get(code));
        }
        return view.displayValues().getOrDefault(
                code, view.values().getOrDefault(code, ""));
    }

    private static List<String> businessCodes(LogisticsImportDefinition definition) {
        return LogisticsImportTemplate.codes(definition).stream()
                .filter(code -> !BusinessImportWorkbook.PHOTO_FILENAMES_CODE.equals(code))
                .toList();
    }
}
