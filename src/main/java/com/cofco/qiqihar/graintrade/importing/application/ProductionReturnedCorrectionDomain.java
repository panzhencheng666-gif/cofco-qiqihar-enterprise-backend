package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.production.application.ProductionDraft;
import com.cofco.qiqihar.graintrade.production.application.ProductionImportDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordView;
import com.cofco.qiqihar.graintrade.production.application.ProductionSurveyField;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.production.domain.ProductionStatus;
import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.ConflictException;
import com.cofco.qiqihar.graintrade.shared.application.PlainDecimal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProductionReturnedCorrectionDomain
        implements OperationalReturnedCorrectionDomain {
    private static final int PAGE_SIZE = 100;
    private final ProductionRecordService production;
    private final BusinessImportTemplateCatalog catalog;
    private final RegionImportResolver regions;

    public ProductionReturnedCorrectionDomain(
            ProductionRecordService production,
            BusinessImportTemplateCatalog catalog,
            RegionImportResolver regions) {
        this.production = production;
        this.catalog = catalog;
        this.regions = regions;
    }

    @Override public String domainCode() { return ProductionImportTemplate.DOMAIN; }
    @Override public String domainLabel() { return "产情"; }

    @Override
    public BusinessImportWorkbook.Template workbook(String productCode) {
        Definitions definitions = definitions(productCode);
        return ProductionImportTemplate.productWorkbook(
                productCode, definitions.items(), definitions.objectTypes());
    }

    @Override
    public List<ReturnedRecord> returned(String productCode) {
        Definitions definitions = definitions(productCode);
        Map<String, String> objectLabels = definitions.objectTypes().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        BusinessImportTemplateCatalog.ObjectTypeOption::code,
                        BusinessImportTemplateCatalog.ObjectTypeOption::label));
        List<String> codes = businessCodes(productCode, definitions.items());
        ArrayList<ReturnedRecord> result = new ArrayList<>();
        for (int page = 0;; page++) {
            var records = production.read(new ProductionRecordQuery(
                    productCode, "MONITORING", page, PAGE_SIZE,
                    Map.of("status", ProductionStatus.RETURNED.name())));
            for (var item : records.items()) {
                ProductionRecordView view = production.detail(item.id());
                if (view.record().status() == ProductionStatus.RETURNED
                        && view.allowedActions().containsAll(List.of("SAVE", "SUBMIT"))) {
                    result.add(new ReturnedRecord(item.id(), view.record().version(),
                            codes.stream().map(code -> value(
                                    code, view, objectLabels)).toList()));
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
        ProductionRecordView original = production.detail(originalId);
        if (!productCode.equals(original.record().productCode())) {
            throw new ClientRequestException(
                    "PRODUCTION_RETURNED_CORRECTION_PRODUCT_MISMATCH",
                    "原记录与修正表品种不一致");
        }
        if (original.record().status() != ProductionStatus.RETURNED) {
            throw new ConflictException(
                    "PRODUCTION_RETURNED_CORRECTION_STATE_CONFLICT",
                    "原记录已不是可修正的退回状态");
        }
        if (!original.allowedActions().containsAll(List.of("SAVE", "SUBMIT"))) {
            throw new AccessDeniedException(
                    "PRODUCTION_RETURNED_CORRECTION_NOT_ALLOWED", "当前账号无权修正该记录");
        }
        Definitions definitions = definitions(productCode);
        List<String> codes = businessCodes(productCode, definitions.items());
        if (codes.size() != suppliedValues.size()) {
            throw new ClientRequestException(
                    "INVALID_IMPORT_TEMPLATE", "修正表字段与当前产情表单不一致");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < codes.size(); index++) {
            values.put(codes.get(index), suppliedValues.get(index).trim());
        }
        Map<String, String> objectCodes = definitions.objectTypes().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        BusinessImportTemplateCatalog.ObjectTypeOption::label,
                        BusinessImportTemplateCatalog.ObjectTypeOption::code));
        String objectValue = values.getOrDefault("objectTypeCode", "");
        String objectType = objectCodes.getOrDefault(objectValue, objectValue);
        ProductionImportDefinition definition = definitions.items().stream()
                .filter(item -> item.objectTypeCode().equals(objectType))
                .findFirst().orElseThrow(() -> new ClientRequestException(
                        "IMPORT_OBJECT_TYPE_INVALID", "样本点类型不在当前品种的填报范围内"));
        ProductionDraft draft = draft(definition, values);
        ProductionRecordView saved = production.saveDraft(
                originalId, originalVersion, draft);
        return production.submit(originalId, saved.record().version()).record().id();
    }

    private ProductionDraft draft(
            ProductionImportDefinition definition, Map<String, String> values) {
        try {
            String regionCode = regions.resolve(required(values, "regionCode"));
            int year = Integer.parseInt(required(values, "surveyYear"));
            String monthValue = values.getOrDefault("surveyMonth", "");
            Integer month = monthValue.isBlank() ? null : Integer.valueOf(monthValue);
            LocalDate surveyDate = LocalDate.of(year, month == null ? 1 : month, 1);
            Map<String, String> metadata = new LinkedHashMap<>();
            ProductionImportTemplate.SUBMISSION_METADATA_HEADERS.forEach(code ->
                    metadata.put(code, values.getOrDefault(code, "")));
            ProductionImportTemplate.DETAIL_HEADERS.forEach(code -> {
                String value = values.get(code);
                if (value != null && !value.isBlank()) metadata.put(code, value);
            });
            Map<String, BigDecimal> quality = new LinkedHashMap<>();
            Map<String, BigDecimal> costs = new LinkedHashMap<>();
            Map<String, BigDecimal> insurance = new LinkedHashMap<>();
            Map<String, BigDecimal> subsidies = new LinkedHashMap<>();
            for (ProductionImportDefinition.Group group : definition.groups()) {
                if ("DETAIL".equals(group.code())) continue;
                Map<String, BigDecimal> target = switch (group.code()) {
                    case "QUALITY" -> quality;
                    case "COST" -> costs;
                    case "INSURANCE" -> insurance;
                    case "SUBSIDY" -> subsidies;
                    default -> throw new ClientRequestException(
                            "INVALID_IMPORT_TEMPLATE", "产情修正字段分组无效");
                };
                for (ProductionImportDefinition.Field field : group.fields()) {
                    String value = values.get(field.code());
                    if (value != null && !value.isBlank()) {
                        target.put(field.code(), PlainDecimal.parse(value,
                                field.precision() - field.scale(), field.scale(),
                                "IMPORT_ROW_VALUE_FORMAT"));
                    }
                }
            }
            return new ProductionDraft(
                    definition.productCode(), definition.objectTypeCode(), regionCode, null,
                    surveyDate,
                    PlainDecimal.parse(required(values, "cultivatedAreaMu"),
                            14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    PlainDecimal.parse(required(values, "yieldPerMuKilograms"),
                            14, 4, "IMPORT_ROW_VALUE_FORMAT"),
                    quality, costs, insurance, subsidies, metadata, List.of(), year, month);
        } catch (ClientRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ClientRequestException(
                    "IMPORT_ROW_VALUE_FORMAT", "产情修正行的日期或数值格式不正确");
        }
    }

    private Definitions definitions(String productCode) {
        var objectTypes = catalog.objectTypes(domainCode(), productCode);
        return new Definitions(objectTypes, objectTypes.stream().map(option ->
                production.importDefinition(productCode, option.code())).toList());
    }

    private static List<String> businessCodes(
            String productCode, List<ProductionImportDefinition> definitions) {
        return ProductionImportTemplate.productCodes(productCode, definitions).stream()
                .filter(code -> !BusinessImportWorkbook.PHOTO_FILENAMES_CODE.equals(code))
                .toList();
    }

    private String value(String code, ProductionRecordView view,
            Map<String, String> objectLabels) {
        var record = view.record();
        if ("objectTypeCode".equals(code)) {
            return objectLabels.getOrDefault(record.objectTypeCode(), record.objectTypeCode());
        }
        if ("regionCode".equals(code)) return regions.displayPath(record.regionCode());
        if ("surveyYear".equals(code)) return Integer.toString(record.surveyYear());
        if ("surveyMonth".equals(code)) {
            return record.surveyMonth() == null ? "" : Integer.toString(record.surveyMonth());
        }
        if ("cultivatedAreaMu".equals(code)) return decimal(record.cultivatedAreaMu());
        if ("yieldPerMuKilograms".equals(code)) return decimal(record.yieldPerMuKilograms());
        String metadata = record.submissionMetadata().get(code);
        if (metadata != null) return metadata;
        BigDecimal fact = java.util.stream.Stream.of(
                        record.quality(), record.costs(), record.insurance(), record.subsidies())
                .map(map -> map.get(code)).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        return fact == null ? "" : decimal(fact);
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String required(Map<String, String> values, String code) {
        String value = values.get(code);
        if (value == null || value.isBlank()) {
            throw new ClientRequestException(
                    "IMPORT_ROW_REQUIRED_VALUE", "产情修正行存在未填写的必填项");
        }
        return value;
    }

    private record Definitions(
            List<BusinessImportTemplateCatalog.ObjectTypeOption> objectTypes,
            List<ProductionImportDefinition> items) {
        Definitions {
            objectTypes = List.copyOf(objectTypes);
            items = List.copyOf(items);
        }
    }
}
